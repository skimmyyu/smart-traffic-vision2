<template>
  <div class="management-page">
    <div class="page-head"><div><h2>设备管理</h2><p>摄像头注册、视频地址配置与实际画面在线状态监控。</p></div><el-button type="primary" @click="dialogVisible = true">注册边缘设备</el-button></div>
    <el-row :gutter="12" class="summary"><el-col :span="8"><el-card shadow="never"><b>{{ devices.length }}</b><span>设备总数</span></el-card></el-col><el-col :span="8"><el-card shadow="never"><b class="online">{{ onlineCount }}</b><span>在线设备</span></el-card></el-col><el-col :span="8"><el-card shadow="never"><b>{{ devices.length - onlineCount }}</b><span>离线设备</span></el-card></el-col></el-row>
    <el-card shadow="never">
      <template #header><div class="card-head"><span>边缘设备列表</span><div class="card-head-actions"><small>状态由实际画面自动检测</small><el-button type="primary" @click="load">刷新状态</el-button></div></div></template>
      <el-table v-loading="loading" :data="devices" stripe empty-text="暂无设备">
        <el-table-column prop="name" label="设备名称" min-width="210" />
        <el-table-column label="类型" width="120"><template #default="{ row }"><el-tag effect="plain" :type="deviceType(row).tag">{{ deviceType(row).name }}</el-tag></template></el-table-column>
        <el-table-column prop="ip" label="IP地址/来源" width="150" />
        <el-table-column label="在线状态" width="120"><template #default="{ row }"><el-tag :type="row.status === 'online' ? 'success' : 'info'">{{ row.status === 'online' ? '在线' : '离线' }}</el-tag></template></el-table-column>
        <el-table-column prop="streamUrl" label="视频流地址" min-width="260" show-overflow-tooltip /><el-table-column prop="lastOnline" label="最后收到画面" width="180" />
      </el-table>
    </el-card>
    <el-dialog v-model="dialogVisible" title="注册边缘设备" width="500px">
      <el-form label-width="100px"><el-form-item label="设备名称"><el-input v-model="form.name" /></el-form-item><el-form-item label="IP地址"><el-input v-model="form.ip" /></el-form-item><el-form-item label="视频流地址"><el-input v-model="form.streamUrl" /></el-form-item><el-form-item label="初始状态"><el-select v-model="form.status" style="width:100%"><el-option label="在线" value="online"/><el-option label="离线" value="offline"/></el-select></el-form-item></el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchDevices, registerDevice } from '../api/device.js'
const devices=ref([]), loading=ref(false), saving=ref(false), dialogVisible=ref(false)
const form=reactive({name:'704沙盘摄像头',ip:'10.126.59.120',streamUrl:'rtsp://10.126.59.120:8554/live/live12',status:'online'})
const onlineCount=computed(()=>devices.value.filter(x=>x.status==='online').length)
function deviceType(row){const url=row.streamUrl||'';if(url.startsWith('browser-camera://local'))return{name:'电脑摄像头',tag:'primary'};if(url.startsWith('browser-camera://phone'))return{name:'手机摄像头',tag:'warning'};if(url.includes('/live/live'))return{name:'沙盘摄像头',tag:'success'};return{name:'边缘设备',tag:'info'}}
async function load(){loading.value=true;try{devices.value=await fetchDevices()}catch(e){ElMessage.error(e.message)}finally{loading.value=false}}
async function save(){if(!form.name.trim())return ElMessage.warning('请输入设备名称');saving.value=true;try{await registerDevice({...form});dialogVisible.value=false;await load();ElMessage.success('设备注册成功')}catch(e){ElMessage.error(e.message)}finally{saving.value=false}}
onMounted(load)
</script>
<style scoped>
.management-page{max-width:1300px;margin:0 auto}.page-head,.card-head,.card-head-actions{display:flex;align-items:center;justify-content:space-between}.card-head-actions{gap:12px}.card-head-actions small{color:#64748b}.page-head{margin-bottom:16px}.page-head h2{margin:0 0 6px}.page-head p{margin:0;color:#64748b}.summary{margin-bottom:16px}.summary :deep(.el-card__body){display:flex;flex-direction:column;align-items:center}.summary b{font-size:30px}.summary .online{color:#16a34a}.summary span{margin-top:4px;color:#64748b}
</style>
