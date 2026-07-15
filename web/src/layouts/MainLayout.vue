<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="header-left">
        <span class="title">智慧交通视觉感知系统</span>
        <el-tag type="info">V0.1</el-tag>
      </div>
      <el-menu
        mode="horizontal"
        :default-active="activeMenu"
        router
        class="nav-menu"
        :ellipsis="false"
      >
        <el-menu-item index="/monitor">监控中心</el-menu-item>
        <el-menu-item index="/gate">闸机</el-menu-item>
        <el-menu-item index="/parking">禁停</el-menu-item>
        <el-menu-item index="/heatmap">热力图</el-menu-item>
        <el-menu-item index="/scene-anomaly">场景异常</el-menu-item>
        <el-menu-item index="/media-recognition">离线识别</el-menu-item>
        <el-sub-menu index="data-center">
          <template #title>数据中心</template>
          <el-menu-item index="/records">通行记录</el-menu-item>
          <el-menu-item index="/alerts">告警中心</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="system-center">
          <template #title>系统管理</template>
          <el-menu-item index="/dashboard">系统管理</el-menu-item>
          <el-menu-item index="/devices">设备管理</el-menu-item>
          <el-menu-item index="/models">模型管理</el-menu-item>
          <el-menu-item index="/whitelist">白名单管理</el-menu-item>
          <el-menu-item index="/statistics">数据统计和历史查询</el-menu-item>
        </el-sub-menu>
      </el-menu>
      <div class="header-right">
        <span class="user-name">{{ username }}</span>
        <el-button type="danger" link @click="onLogout">退出</el-button>
      </div>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentUser, logout } from '../utils/auth.js'

const route = useRoute()
const router = useRouter()
const activeMenu = computed(() => route.path)
const username = computed(() => currentUser() || 'admin')

function onLogout() {
  logout()
  router.replace({ path: '/login', query: { redirect: route.fullPath } })
}
</script>

<style scoped>
.layout {
  min-height: 100vh;
  background: #f5f7fa;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.title {
  font-size: 18px;
  font-weight: 600;
}
.nav-menu {
  flex: 1;
  justify-content: flex-end;
  border-bottom: none;
  background: transparent;
  min-width: 0;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.user-name {
  font-size: 13px;
  color: #64748b;
}
:deep(.el-main) {
  padding: 16px;
  overflow: hidden;
}
</style>
