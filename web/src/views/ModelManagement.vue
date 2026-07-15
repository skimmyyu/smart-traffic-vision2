<template>
  <div class="management-page">
    <div class="page-head"><div><h2>模型管理</h2><p>模型选择、运行状态及检测参数配置，保存后立即生效。</p></div><el-button type="primary" :loading="saving" @click="save">保存当前参数</el-button></div>
    <el-alert type="success" :closable="false" show-icon title="车辆和车牌识别继续使用小组原模型" description="sandbox-car-v4/best.pt + plate_det.onnx + ppocr.onnx 未被替换。" />
    <el-row :gutter="16" class="content">
      <el-col :span="9"><el-card shadow="never"><template #header>可用模型</template><div v-for="model in models" :key="model.id" class="model-item" :class="{ active:model.id===selectedId }" @click="select(model.id)"><div><b>{{ model.name }}</b><small>{{ model.description }}</small></div><el-tag v-if="model.active" type="success">运行中</el-tag></div></el-card></el-col>
      <el-col :span="15"><el-card shadow="never"><template #header><div class="card-head"><span>{{ selected?.name || '参数配置' }}</span><el-button :disabled="selected?.active" @click="activate">设为当前模型</el-button></div></template>
        <el-form v-loading="loadingParams" label-width="150px" class="params">
          <el-form-item v-for="(value,key) in parameters" :key="key" :label="label(key)"><el-input-number v-model="parameters[key]" :min="minimum(key)" :max="maximum(key)" :step="step(key)" controls-position="right" /><span class="unit">{{ unit(key) }}</span></el-form-item>
        </el-form>
        <el-alert v-if="selectedId==='parking'" type="warning" :closable="false" show-icon :title="`当前禁停告警时间：${parameters.dwellSeconds || 20} 秒`" description="车辆在禁停区域内持续静止达到该时间后触发告警。禁停监控页面也可以直接调整。" />
        <el-alert v-if="selectedId==='anomaly'" type="info" :closable="false" show-icon :title="`最低显示置信度 ${parameters.minConfidencePercent || 60}% · 忽略小于画面 ${parameters.minAreaPercent || 3}% 的变化`" description="候选异常会继续积累置信度，达到最低置信度后才显示；面积默认 3%，可过滤较小动态干扰。" />
      </el-card></el-col>
    </el-row>
  </div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchModels, fetchModelParameters, switchModel, updateModelParameters } from '../api/model.js'
const models=ref([]),selectedId=ref('yolov8n'),loadingParams=ref(false),saving=ref(false),parameters=reactive({})
const selected=computed(()=>models.value.find(x=>x.id===selectedId.value))
const labels={confidence:'检测置信度',imageSize:'输入尺寸',intervalMs:'推理间隔',ocrConfidence:'OCR置信度',dwellSeconds:'禁停告警时间',stillThresholdPx:'静止容差',diffThreshold:'画面差异阈值',minAreaPercent:'最小异常区域占比',minConfidencePercent:'最低显示置信度',persistenceFrames:'异常确认帧数'}
function label(k){return labels[k]||k} function unit(k){return k==='dwellSeconds'?'秒':k==='intervalMs'?'毫秒':k==='stillThresholdPx'?'像素':k==='minAreaPercent'||k==='minConfidencePercent'?'%':''} function step(k){return k==='minAreaPercent'?0.05:k==='minConfidencePercent'?5:k.toLowerCase().includes('confidence')?0.05:1} function minimum(k){return k==='minAreaPercent'?0.01:k==='minConfidencePercent'?0:k.toLowerCase().includes('confidence')?0.05:1} function maximum(k){return k==='minAreaPercent'?12:k==='minConfidencePercent'?100:undefined}
async function select(id){selectedId.value=id;loadingParams.value=true;try{const value=await fetchModelParameters(id);Object.keys(parameters).forEach(k=>delete parameters[k]);Object.assign(parameters,value)}catch(e){ElMessage.error(e.message)}finally{loadingParams.value=false}}
async function load(){models.value=await fetchModels();await select(models.value.find(x=>x.active)?.id||'yolov8n')}
async function save(){saving.value=true;try{Object.assign(parameters,await updateModelParameters(selectedId.value,parameters));ElMessage.success('参数已保存并实时应用')}catch(e){ElMessage.error(e.message)}finally{saving.value=false}}
async function activate(){try{await switchModel(selectedId.value);models.value=await fetchModels();ElMessage.success(`已切换至${selected.value?.name}`)}catch(e){ElMessage.error(e.message)}}
onMounted(()=>load().catch(e=>ElMessage.error(e.message)))
</script>
<style scoped>
.management-page{max-width:1250px;margin:0 auto}.page-head,.card-head{display:flex;align-items:center;justify-content:space-between}.page-head{margin-bottom:16px}.page-head h2{margin:0 0 6px}.page-head p{margin:0;color:#64748b}.content{margin-top:16px}.model-item{display:flex;justify-content:space-between;gap:10px;padding:13px;margin-bottom:8px;border:1px solid #e2e8f0;border-radius:8px;cursor:pointer}.model-item.active{border-color:#409eff;background:#eff6ff}.model-item small{display:block;margin-top:4px;color:#64748b}.params{padding:10px 20px}.unit{margin-left:8px;color:#64748b}
</style>
