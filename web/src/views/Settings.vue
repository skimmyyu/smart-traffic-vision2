<template>
  <div class="settings">
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>白名单管理</span>
              <el-button type="primary" link @click="loadWhitelist">刷新</el-button>
            </div>
          </template>

          <el-form inline class="add-form" @submit.prevent>
            <el-form-item label="车牌号码">
              <el-input
                v-model="newPlate"
                placeholder="如 京A12345"
                clearable
                style="width: 180px"
                @keyup.enter="handleAddPlate"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="addingPlate" @click="handleAddPlate">
                添加
              </el-button>
            </el-form-item>
          </el-form>

          <el-table v-loading="loadingWhitelist" :data="whitelist" stripe empty-text="暂无白名单">
            <el-table-column prop="id" label="编号" width="80" />
            <el-table-column prop="plateNumber" label="车牌号码" />
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }">
                <el-button type="danger" link @click="handleRemovePlate(row.plateNumber)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>边缘设备（704 沙盘摄像头）</span>
              <el-button type="primary" @click="deviceDialogVisible = true">注册设备</el-button>
            </div>
          </template>

          <el-alert
            type="info"
            :closable="false"
            show-icon
            class="device-tip"
            title="当前使用学院 704 沙盘固定摄像头，12 路 RTSP 无需账号密码。"
            description="服务器 10.126.59.120:8554，地址格式 rtsp://10.126.59.120:8554/live/liveN（N=1～12）。切换监控通道请在前端监控页操作，MediaMTX 会自动更新拉流源。"
          />

          <el-table v-loading="loadingDevices" :data="devices" stripe empty-text="暂无设备" class="device-table">
            <el-table-column prop="name" label="设备名称" min-width="120" />
            <el-table-column prop="ip" label="IP" width="130" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'online' ? 'success' : 'info'" size="small">
                  {{ row.status === 'online' ? '在线' : '离线' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="streamUrl" label="视频流" min-width="160" show-overflow-tooltip />
            <el-table-column prop="lastOnline" label="最后在线" width="170" />
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }">
                <el-button
                  type="primary"
                  link
                  :loading="heartbeatId === row.id"
                  @click="handleHeartbeat(row.id)"
                >
                  心跳
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="hover" class="model-card">
      <template #header>
        <div class="card-header">
          <div>
            <span>模型管理与参数配置</span>
            <div class="header-sub">选择运行模型并调整实时阈值；异常差分和禁停参数保存后立即生效</div>
          </div>
          <el-button type="primary" :loading="savingParameters" @click="saveParameters">保存参数</el-button>
        </div>
      </template>
      <div class="model-toolbar">
        <el-select v-model="selectedModelId" style="width: 290px" @change="loadParameters">
          <el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id">
            <span>{{ model.name }}</span><el-tag v-if="model.active" type="success" size="small" class="active-tag">运行中</el-tag>
          </el-option>
        </el-select>
        <el-button @click="activateSelectedModel">设为当前模型</el-button>
        <span class="model-description">{{ selectedModel?.description }}</span>
      </div>
      <el-form v-loading="loadingParameters" inline class="parameter-form" label-position="top">
        <el-form-item v-for="(value, key) in modelParameters" :key="key" :label="parameterLabel(key)">
          <el-input-number v-model="modelParameters[key]" :step="parameterStep(key)" :min="parameterMin(key)" controls-position="right" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-dialog v-model="deviceDialogVisible" title="注册边缘设备" width="480px" @closed="resetDeviceForm">
      <el-form ref="deviceFormRef" :model="deviceForm" :rules="deviceRules" label-width="96px">
        <el-form-item label="设备名称" prop="name">
          <el-input v-model="deviceForm.name" placeholder="如 704沙盘摄像头" />
        </el-form-item>
        <el-form-item label="IP 地址" prop="ip">
          <el-input v-model="deviceForm.ip" placeholder="沙盘 RTSP 服务器，如 10.126.59.120" />
        </el-form-item>
        <el-form-item label="视频流地址" prop="streamUrl">
          <el-input
            v-model="deviceForm.streamUrl"
            placeholder="rtsp://10.126.59.120:8554/live/live12"
          />
        </el-form-item>
        <el-form-item label="设备状态" prop="status">
          <el-select v-model="deviceForm.status" style="width: 100%">
            <el-option label="在线" value="online" />
            <el-option label="离线" value="offline" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deviceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingDevice" @click="handleRegisterDevice">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchDevices, registerDevice, sendHeartbeat } from '../api/device.js'
import { addWhitelist, fetchWhitelist, removeWhitelist } from '../api/whitelist.js'
import { fetchModels, fetchModelParameters, switchModel, updateModelParameters } from '../api/model.js'
import { computed } from 'vue'

const whitelist = ref([])
const devices = ref([])
const loadingWhitelist = ref(false)
const loadingDevices = ref(false)
const addingPlate = ref(false)
const savingDevice = ref(false)
const newPlate = ref('')
const deviceDialogVisible = ref(false)
const deviceFormRef = ref(null)
const heartbeatId = ref(null)
const models = ref([])
const selectedModelId = ref('yolov8n')
const modelParameters = reactive({})
const loadingParameters = ref(false)
const savingParameters = ref(false)
const selectedModel = computed(() => models.value.find((m) => m.id === selectedModelId.value))

const deviceForm = reactive({
  name: '',
  ip: '',
  streamUrl: '',
  status: 'online'
})

const deviceRules = {
  name: [{ required: true, message: '请输入设备名称', trigger: 'blur' }]
}

async function loadWhitelist() {
  loadingWhitelist.value = true
  try {
    whitelist.value = await fetchWhitelist()
  } catch (e) {
    ElMessage.error(e.message || '加载白名单失败')
  } finally {
    loadingWhitelist.value = false
  }
}

async function loadDevices() {
  loadingDevices.value = true
  try {
    devices.value = await fetchDevices()
  } catch (e) {
    ElMessage.error(e.message || '加载设备失败')
  } finally {
    loadingDevices.value = false
  }
}

async function handleAddPlate() {
  const plate = newPlate.value.trim()
  if (!plate) {
    ElMessage.warning('请输入车牌号码')
    return
  }
  addingPlate.value = true
  try {
    await addWhitelist(plate)
    ElMessage.success('添加成功')
    newPlate.value = ''
    await loadWhitelist()
  } catch (e) {
    ElMessage.error(e.message || '添加失败')
  } finally {
    addingPlate.value = false
  }
}

async function handleRemovePlate(plateNumber) {
  try {
    await ElMessageBox.confirm(`确定从白名单删除「${plateNumber}」？`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await removeWhitelist(plateNumber)
    ElMessage.success('已删除')
    await loadWhitelist()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.message || '删除失败')
    }
  }
}

function resetDeviceForm() {
  deviceForm.name = '704沙盘摄像头'
  deviceForm.ip = '10.126.59.120'
  deviceForm.streamUrl = 'rtsp://10.126.59.120:8554/live/live12'
  deviceForm.status = 'online'
  deviceFormRef.value?.clearValidate()
}

async function handleHeartbeat(id) {
  heartbeatId.value = id
  try {
    await sendHeartbeat(id)
    ElMessage.success('心跳已上报')
    await loadDevices()
  } catch (e) {
    ElMessage.error(e.message || '心跳失败')
  } finally {
    heartbeatId.value = null
  }
}

async function handleRegisterDevice() {
  const valid = await deviceFormRef.value?.validate().catch(() => false)
  if (!valid) return

  savingDevice.value = true
  try {
    await registerDevice({ ...deviceForm })
    ElMessage.success('设备注册成功')
    deviceDialogVisible.value = false
    await loadDevices()
  } catch (e) {
    ElMessage.error(e.message || '注册失败')
  } finally {
    savingDevice.value = false
  }
}

async function loadModelsAndParameters() {
  models.value = await fetchModels()
  selectedModelId.value = models.value.find((m) => m.active)?.id || 'yolov8n'
  await loadParameters()
}

async function loadParameters() {
  loadingParameters.value = true
  try {
    const value = await fetchModelParameters(selectedModelId.value)
    Object.keys(modelParameters).forEach((k) => delete modelParameters[k])
    Object.assign(modelParameters, value)
  } catch (e) { ElMessage.error(e.message || '加载模型参数失败') }
  finally { loadingParameters.value = false }
}

async function saveParameters() {
  savingParameters.value = true
  try {
    Object.assign(modelParameters, await updateModelParameters(selectedModelId.value, modelParameters))
    ElMessage.success('模型参数已保存并应用')
  } catch (e) { ElMessage.error(e.message || '参数保存失败') }
  finally { savingParameters.value = false }
}

async function activateSelectedModel() {
  await switchModel(selectedModelId.value)
  models.value = await fetchModels()
  ElMessage.success(`已切换至${selectedModel.value?.name || '所选模型'}`)
}

const PARAM_LABELS = { confidence: '检测置信度', imageSize: '输入尺寸', intervalMs: '推理间隔(ms)', ocrConfidence: 'OCR置信度', dwellSeconds: '禁停时长(秒)', stillThresholdPx: '静止容差(px)', diffThreshold: '画面差异阈值', persistenceFrames: '持续帧数' }
function parameterLabel(key) { return PARAM_LABELS[key] || key }
function parameterStep(key) { return key.toLowerCase().includes('confidence') ? 0.05 : 1 }
function parameterMin(key) { return key.toLowerCase().includes('confidence') ? 0.05 : 1 }

onMounted(() => {
  loadWhitelist()
  loadDevices()
  loadModelsAndParameters().catch((e) => ElMessage.error(e.message || '加载模型失败'))
})
</script>

<style scoped>
.settings {
  max-width: 1200px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.add-form {
  margin-bottom: 12px;
}
.device-tip {
  margin-bottom: 12px;
}
.device-table {
  margin-top: 4px;
}
.model-card { margin-top: 16px; }
.header-sub { margin-top: 4px; font-size: 12px; color: #909399; font-weight: 400; }
.model-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.active-tag { margin-left: 12px; }.model-description { color: #64748b; font-size: 13px; }
.parameter-form { padding: 14px 16px 0; background: #f8fafc; border-radius: 8px; }
</style>
