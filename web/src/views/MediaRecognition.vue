<template>
  <div class="media-page">
    <div class="page-title">
      <div>
        <h2>图片 / 视频离线识别</h2>
        <p>没有沙盘码流时，上传素材验证车辆检测、车牌 OCR 与白名单通行决策。</p>
      </div>
      <el-tag type="success" size="large">复用 sandbox-car-v4 + RapidOCR</el-tag>
    </div>

    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never">
          <el-upload
            ref="uploadRef"
            v-model:file-list="uploadFiles"
            drag
            multiple
            :auto-upload="false"
            :show-file-list="true"
            :limit="20"
            accept="image/*,video/*,.mkv,.avi,.mov,.webm"
          >
            <div class="upload-icon">＋</div>
            <div class="el-upload__text">拖入图片或视频，或点击选择</div>
            <template #tip>
              <div class="el-upload__tip">支持 JPG、PNG、MP4、AVI、MOV、MKV、WebM，最大 500MB</div>
            </template>
          </el-upload>
          <el-button
            type="primary"
            size="large"
            class="recognize-btn"
            :disabled="!uploadFiles.length || submitting"
            :loading="submitting"
            @click="startRecognition"
          >
            {{ submitting ? '正在提交…' : `开始识别（${uploadFiles.length} 项）` }}
          </el-button>
          <el-alert
            class="tip"
            type="info"
            :closable="false"
            title="视频会抽帧识别并生成带框结果视频"
            description="视频按时间抽帧加速处理；可一次提交多项，最多并行识别 2 项，其余自动排队。完成后会自动进入识别历史。"
            show-icon
          />
        </el-card>

        <el-card v-if="jobs.length" shadow="never" class="jobs-card">
          <template #header><span>识别任务</span></template>
          <div v-for="job in jobs" :key="job.jobId || job.localId" class="job-item">
            <div class="job-line">
              <span class="job-name">{{ job.originalName }}</span>
              <el-tag :type="jobType(job.status)" size="small">{{ jobStatus(job.status) }}</el-tag>
            </div>
            <el-progress :percentage="Number(job.progress || 0)" :status="job.status === 'failed' ? 'exception' : job.status === 'completed' ? 'success' : ''" />
            <small>{{ job.message || '正在上传素材' }}</small>
          </div>
        </el-card>

        <el-card v-if="result" shadow="never" class="summary-card">
          <template #header>识别汇总</template>
          <el-row :gutter="8">
            <el-col :span="12"><div class="metric"><b>{{ result.maxVehicleCount || 0 }}</b><span>最大车辆数</span></div></el-col>
            <el-col :span="12"><div class="metric"><b>{{ result.plates?.length || 0 }}</b><span>识别车牌数</span></div></el-col>
            <el-col :span="12"><div class="metric"><b>{{ result.analyzedFrames || 0 }}</b><span>分析帧数</span></div></el-col>
            <el-col :span="12"><div class="metric"><b>{{ result.durationSeconds || '-' }}</b><span>视频时长(秒)</span></div></el-col>
          </el-row>
        </el-card>

        <el-card shadow="never" class="history-card">
          <template #header>
            <div class="history-head"><span>识别历史</span><el-button link type="primary" @click="loadHistory">刷新</el-button></div>
          </template>
          <el-scrollbar height="300px">
            <button
              v-for="item in history"
              :key="item.resultId"
              class="history-item"
              :class="{ active: result?.resultId === item.resultId }"
              @click="viewHistory(item)"
            >
              <span class="history-type">{{ item.mediaType === 'video' ? '视频' : '图片' }}</span>
              <span class="history-main"><b>{{ item.originalName }}</b><small>{{ formatTime(item.createdAt) }} · 车辆 {{ item.maxVehicleCount || 0 }} · 车牌 {{ item.plates?.length || 0 }}</small></span>
            </button>
            <el-empty v-if="!history.length" description="暂无识别记录" :image-size="48" />
          </el-scrollbar>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card shadow="never" class="result-card">
          <template #header>
            <div class="result-head"><span>标注结果</span><span v-if="result">{{ result.originalName }}</span></div>
          </template>
          <div class="result-preview">
            <video v-if="result?.mediaType === 'video'" :src="resultMediaUrl" controls class="media" />
            <img v-else-if="result?.mediaType === 'image'" :src="resultMediaUrl" class="media" alt="识别结果" />
            <el-empty v-else description="上传素材后在此查看检测框与结果" />
          </div>
        </el-card>

        <el-card v-if="result" shadow="never" class="plates-card">
          <template #header>车牌识别与通行决策</template>
          <el-table :data="result.plates || []" stripe empty-text="未识别到有效车牌">
            <el-table-column prop="plateNumber" label="车牌号码" min-width="130" />
            <el-table-column label="置信度" width="110">
              <template #default="{ row }">{{ ((row.confidence || 0) * 100).toFixed(1) }}%</template>
            </el-table-column>
            <el-table-column label="白名单决策" min-width="180">
              <template #default="{ row }">
                <el-tag :type="row.decision === 'allow' ? 'success' : 'danger'">{{ row.decisionText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="vehicleClass" label="车辆类型" width="110" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { absoluteResultUrl, fetchRecognitionHistory, fetchRecognitionJobs, submitRecognitionJob } from '../api/mediaRecognition.js'

const uploadRef = ref(null)
const uploadFiles = ref([])
const submitting = ref(false)
const jobs = ref([])
const result = ref(null)
const history = ref([])
let jobTimer = null
const completedSeen = new Set()
const resultMediaUrl = computed(() => absoluteResultUrl(result.value?.resultUrl))

async function startRecognition() {
  const files = uploadFiles.value.map((item) => item.raw).filter(Boolean)
  if (!files.length) return
  submitting.value = true
  uploadRef.value?.clearFiles()
  uploadFiles.value = []
  try {
    const local = files.map((file, index) => ({ localId: `${Date.now()}-${index}`, originalName: file.name, status: 'uploading', progress: 0, message: '正在上传素材' }))
    jobs.value = [...local, ...jobs.value]
    const settled = await Promise.allSettled(files.map((file, index) => submitRecognitionJob(file, (p) => {
      local[index].progress = Math.min(99, p)
      jobs.value = [...jobs.value]
    })))
    settled.forEach((item, index) => {
      const pos = jobs.value.findIndex((job) => job.localId === local[index].localId)
      if (item.status === 'fulfilled') {
        if (pos >= 0) jobs.value.splice(pos, 1, item.value)
      } else if (pos >= 0) {
        jobs.value.splice(pos, 1, { ...local[index], status: 'failed', message: item.reason?.response?.data?.message || item.reason?.message || '提交失败' })
      }
    })
    jobs.value = [...jobs.value]
    ElMessage.success(`已提交 ${settled.filter((x) => x.status === 'fulfilled').length} 项识别任务`)
    await pollJobs()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '提交任务失败')
  } finally {
    submitting.value = false
  }
}

async function pollJobs() {
  try {
    const serverJobs = await fetchRecognitionJobs()
    const uploadJobs = jobs.value.filter((job) => job.status === 'uploading')
    jobs.value = [...uploadJobs, ...serverJobs].slice(0, 20)
    let historyChanged = false
    for (const job of serverJobs) {
      if (job.status === 'completed' && !completedSeen.has(job.jobId)) {
        completedSeen.add(job.jobId)
        historyChanged = true
        if (job.result) result.value = job.result
        ElMessage.success(`${job.originalName} 识别完成，已加入历史`)
      }
    }
    if (historyChanged) await loadHistory()
  } catch { /* 后台重启时下一轮自动恢复 */ }
}

async function loadHistory() {
  try { history.value = await fetchRecognitionHistory() }
  catch (e) { ElMessage.error(e.message || '加载识别历史失败') }
}

function viewHistory(item) { result.value = item }
function formatTime(ms) { return ms ? new Date(ms).toLocaleString() : '-' }
function jobStatus(status) { return ({ uploading: '上传中', queued: '排队中', processing: '识别中', completed: '已完成', failed: '失败' })[status] || status }
function jobType(status) { return status === 'completed' ? 'success' : status === 'failed' ? 'danger' : status === 'queued' ? 'info' : 'warning' }
onMounted(async () => {
  await loadHistory()
  for (const item of history.value) completedSeen.add(item.resultId)
  await pollJobs()
  jobTimer = window.setInterval(pollJobs, 800)
})
onUnmounted(() => { if (jobTimer) window.clearInterval(jobTimer) })
</script>

<style scoped>
.media-page { max-width: 1440px; margin: 0 auto; }
.page-title, .result-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.page-title { margin-bottom: 16px; }
.page-title h2 { margin: 0 0 6px; }.page-title p { margin: 0; color: #64748b; }
.upload-icon { font-size: 48px; line-height: 1; color: #409eff; margin-bottom: 12px; }
.recognize-btn { width: 100%; margin: 18px 0 14px; }
.tip, .summary-card, .plates-card { margin-top: 16px; }
.result-card { min-height: 590px; }
.result-preview { position: relative; height: 510px; background: #070d19; border-radius: 8px; overflow: hidden; }
.result-preview :deep(.el-empty) { position: absolute; inset: 0; display: grid; place-content: center; }
.media { position: absolute; inset: 0; display: block; width: 100%; height: 100%; max-width: none; max-height: none; object-fit: contain !important; object-position: center center; background: #070d19; }
.metric { margin: 4px 0; padding: 14px 8px; text-align: center; background: #f8fafc; border-radius: 8px; }
.metric b { display: block; font-size: 25px; color: #2563eb; }.metric span { font-size: 12px; color: #64748b; }
.history-card { margin-top: 16px; }.history-head { display: flex; justify-content: space-between; align-items: center; }
.jobs-card { margin-top: 16px; }.job-item { padding: 8px 0 12px; border-bottom: 1px solid #eef2f7; }.job-item:last-child { border-bottom: 0; }.job-line { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 6px; }.job-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.job-item small { color: #64748b; }
.history-item { width: 100%; display: flex; gap: 10px; align-items: center; padding: 10px; margin-bottom: 7px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; text-align: left; cursor: pointer; }
.history-item:hover, .history-item.active { border-color: #409eff; background: #eff6ff; }
.history-type { flex: none; padding: 4px 7px; color: #2563eb; background: #dbeafe; border-radius: 5px; font-size: 12px; }
.history-main { min-width: 0; }.history-main b, .history-main small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.history-main small { margin-top: 4px; color: #64748b; }
</style>
