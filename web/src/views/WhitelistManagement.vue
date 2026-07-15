<template>
  <div class="management-page">
    <div class="page-head">
      <div><h2>白名单管理</h2><p>白名单车辆写入本地数据库，车牌识别后据此给出允许或拒绝通行决策。</p></div>
      <el-tag type="success" size="large">已落库 {{ whitelist.length }} 辆</el-tag>
    </div>
    <el-card shadow="never">
      <el-form inline @submit.prevent>
        <el-form-item label="车牌号码"><el-input v-model="newPlate" placeholder="如 京A12345" clearable @keyup.enter="addPlate" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="adding" @click="addPlate">添加到白名单</el-button></el-form-item>
        <el-form-item><el-button @click="load">刷新</el-button></el-form-item>
      </el-form>
      <el-table v-loading="loading" :data="whitelist" stripe empty-text="暂无白名单车辆">
        <el-table-column prop="id" label="编号" width="100" />
        <el-table-column prop="plateNumber" label="车牌号码" min-width="180"><template #default="{ row }"><b class="plate">{{ row.plateNumber }}</b></template></el-table-column>
        <el-table-column label="存储状态" width="140"><template #default><el-tag type="success">数据库已保存</el-tag></template></el-table-column>
        <el-table-column label="通行决策" width="160"><template #default><span>识别命中后允许通行</span></template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="danger" @click="removePlate(row.plateNumber)">删除</el-button></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addWhitelist, fetchWhitelist, removeWhitelist } from '../api/whitelist.js'
const whitelist = ref([])
const newPlate = ref('')
const loading = ref(false)
const adding = ref(false)
async function load() { loading.value = true; try { whitelist.value = await fetchWhitelist() } catch (e) { ElMessage.error(e.message) } finally { loading.value = false } }
async function addPlate() {
  const plate = newPlate.value.trim().toUpperCase()
  if (!plate) return ElMessage.warning('请输入车牌号码')
  adding.value = true
  try { await addWhitelist(plate); newPlate.value = ''; await load(); ElMessage.success('已写入本地白名单数据库') }
  catch (e) { ElMessage.error(e.message || '添加失败') } finally { adding.value = false }
}
async function removePlate(plate) {
  try { await ElMessageBox.confirm(`确定删除白名单车辆「${plate}」？`, '确认', { type: 'warning' }); await removeWhitelist(plate); await load(); ElMessage.success('已从白名单删除') }
  catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '删除失败') }
}
onMounted(load)
</script>

<style scoped>
.management-page { max-width: 1200px; margin: 0 auto; }.page-head { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; }.page-head h2 { margin:0 0 6px; }.page-head p { margin:0; color:#64748b; }.plate { padding:5px 10px; color:#fff; background:#1677ff; border-radius:5px; letter-spacing:2px; }
</style>
