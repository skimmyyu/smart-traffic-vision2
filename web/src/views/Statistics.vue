<template>
  <div class="statistics-page">
    <div class="page-head"><div><h2>数据统计和历史查询</h2><p>检测车牌数、拥堵度变化趋势、违规停车数、道路异常数和位置分布。</p></div><el-button type="primary" :loading="loading" @click="loadData">刷新全部数据</el-button></div>
    <el-row :gutter="14" class="summary-row">
      <el-col :span="6" v-for="item in summaryCards" :key="item.label"><el-card shadow="hover"><div class="summary-value" :style="{color:item.color}">{{ item.value }}</div><div class="summary-label">{{ item.label }}</div></el-card></el-col>
    </el-row>
    <el-card shadow="never">
      <el-tabs v-model="activeTab" @tab-change="tabChanged">
        <el-tab-pane label="拥堵度变化趋势" name="congestion">
          <div ref="trendRef" class="chart" />
          <div class="table-head"><b>拥堵统计历史</b><el-button link type="primary" @click="downloadExport('congestion')">导出 CSV</el-button></div>
          <el-table :data="logs" stripe max-height="340" empty-text="暂无拥堵记录"><el-table-column prop="vehicleCount" label="车辆数" width="100"/><el-table-column prop="heatmapData" label="路段/热力数据" min-width="300" show-overflow-tooltip/><el-table-column prop="statTime" label="统计时间" width="190"/></el-table>
        </el-tab-pane>
        <el-tab-pane label="车牌识别历史" name="plates">
          <div class="table-head"><b>检测车牌数：{{ plates.length }}</b><el-button link type="primary" @click="downloadExport('plates')">导出 CSV</el-button></div>
<el-table :data="plates" stripe max-height="590" empty-text="暂无车牌记录"><el-table-column prop="plateNumber" label="车牌号码" width="160"><template #default="{row}"><b class="plate">{{ row.plateNumber }}</b></template></el-table-column><el-table-column label="通行结果" width="140"><template #default="{row}"><el-tag :type="row.passResult==='allow'?'success':'danger'">{{ row.passResult==='allow'?'允许通行':'拒绝通行' }}</el-tag></template></el-table-column><el-table-column label="采集摄像头" min-width="180" show-overflow-tooltip><template #default="{row}">{{ row.cameraName || row.cameraId || '历史记录未标注' }}</template></el-table-column><el-table-column prop="recognizedAt" label="识别时间" min-width="200"/></el-table>
        </el-tab-pane>
        <el-tab-pane label="违规停车历史" name="parking">
          <div class="table-head"><b>违规停车记录：{{ parkingAlerts.length }}</b><el-button link type="primary" @click="downloadExport('alerts')">导出 CSV</el-button></div>
          <el-table :data="parkingAlerts" stripe max-height="590" empty-text="暂无违规停车记录"><el-table-column prop="description" label="告警内容" min-width="320"/><el-table-column label="位置" min-width="180"><template #default="{row}">{{ locationText(row) }}</template></el-table-column><el-table-column prop="occurredAt" label="告警时间" width="190"/></el-table>
        </el-tab-pane>
        <el-tab-pane label="道路异常与位置分布" name="anomaly">
          <div ref="locationRef" class="chart location-chart" />
          <div class="table-head"><b>道路异常记录：{{ anomalyAlerts.length }}</b><el-button link type="primary" @click="downloadExport('alerts')">导出 CSV</el-button></div>
          <el-table :data="anomalyAlerts" stripe max-height="340" empty-text="暂无道路异常记录"><el-table-column prop="description" label="异常内容" min-width="300"/><el-table-column label="异常位置" min-width="210"><template #default="{row}"><el-tag type="warning">{{ locationText(row) }}</el-tag></template></el-table-column><el-table-column prop="occurredAt" label="发生时间" width="190"/></el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { fetchCongestionLogs } from '../api/congestion.js'
import { fetchStatisticsOverview } from '../api/statistics.js'
import { fetchAlerts } from '../api/alert.js'
import { fetchPlateRecords } from '../api/plate.js'
import { downloadExport } from '../api/export.js'
const overview=ref({}),logs=ref([]),alerts=ref([]),plates=ref([]),loading=ref(false),activeTab=ref('congestion'),trendRef=ref(null),locationRef=ref(null)
let trendChart=null,locationChart=null
const parkingAlerts=computed(()=>alerts.value.filter(x=>x.alertType==='parking_violation'||String(x.description||'').includes('禁停')))
const anomalyAlerts=computed(()=>alerts.value.filter(x=>x.alertType==='road_anomaly'))
const summaryCards=computed(()=>[
  {label:'今日检测车牌数',value:overview.value.todayPlateCount||0,color:'#2563eb'},
  {label:'今日平均拥堵车辆',value:Number(overview.value.avgVehicleCount||0).toFixed(1),color:'#f59e0b'},
  {label:'今日违规停车数',value:overview.value.todayParkingViolationCount||0,color:'#dc2626'},
  {label:'今日道路异常数',value:overview.value.todayRoadAnomalyCount||0,color:'#7c3aed'}
])
function locationText(row){try{const p=typeof row.location==='string'?JSON.parse(row.location):row.location||{};return p.lane||p.zone||p.channelId||`${p.x??'-'}, ${p.y??'-'}`}catch{return row.location||'未知位置'}}
function locationData(){const map={};for(const row of anomalyAlerts.value){const key=locationText(row);map[key]=(map[key]||0)+1}return Object.entries(map)}
async function loadData(){loading.value=true;try{const [ov,cg,al,pl]=await Promise.all([fetchStatisticsOverview(),fetchCongestionLogs(100),fetchAlerts(200),fetchPlateRecords(200)]);overview.value=ov;logs.value=cg;alerts.value=al;plates.value=pl;await nextTick();drawCurrent()}catch(e){ElMessage.error(e.message||'加载统计数据失败')}finally{loading.value=false}}
function drawTrend(){if(!trendRef.value)return;trendChart||=echarts.init(trendRef.value);const data=[...logs.value].reverse();trendChart.setOption({tooltip:{trigger:'axis'},grid:{left:50,right:20,top:30,bottom:42},xAxis:{type:'category',data:data.map(x=>String(x.statTime||'').replace('T',' ').slice(5,16))},yAxis:{type:'value',name:'车辆数',minInterval:1},series:[{name:'拥堵车辆数',type:'line',smooth:true,areaStyle:{opacity:.2},data:data.map(x=>x.vehicleCount||0),itemStyle:{color:'#f59e0b'}}]})}
function drawLocations(){if(!locationRef.value)return;locationChart||=echarts.init(locationRef.value);const data=locationData();locationChart.setOption({tooltip:{trigger:'axis'},grid:{left:100,right:30,top:20,bottom:35},xAxis:{type:'value',minInterval:1,name:'异常次数'},yAxis:{type:'category',data:data.map(x=>x[0])},series:[{type:'bar',data:data.map(x=>x[1]),itemStyle:{color:'#8b5cf6',borderRadius:[0,5,5,0]}}]})}
function drawCurrent(){if(activeTab.value==='congestion')drawTrend();if(activeTab.value==='anomaly')drawLocations()}
async function tabChanged(){await nextTick();drawCurrent()}
onMounted(()=>{loadData();window.addEventListener('resize',drawCurrent)})
onUnmounted(()=>{window.removeEventListener('resize',drawCurrent);trendChart?.dispose();locationChart?.dispose()})
</script>
<style scoped>
.statistics-page{max-width:1350px;margin:0 auto}.page-head,.table-head{display:flex;align-items:center;justify-content:space-between}.page-head{margin-bottom:16px}.page-head h2{margin:0 0 6px}.page-head p{margin:0;color:#64748b}.summary-row{margin-bottom:16px}.summary-value{font-size:30px;font-weight:750}.summary-label{margin-top:5px;color:#64748b}.chart{height:300px}.location-chart{height:270px}.table-head{padding:8px 0 12px}.plate{padding:4px 9px;color:#fff;background:#1677ff;border-radius:4px;letter-spacing:1px}
</style>
