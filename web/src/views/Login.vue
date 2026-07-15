<template>
  <div class="login-page">
    <div class="bg-grid" aria-hidden="true" />
    <div class="bg-glow bg-glow-a" aria-hidden="true" />
    <div class="bg-glow bg-glow-b" aria-hidden="true" />

    <div class="login-shell">
      <!-- 左侧品牌区 -->
      <aside class="brand-panel">
        <div class="brand-inner">
          <div class="logo-mark">
            <svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="4" y="18" width="40" height="18" rx="4" stroke="currentColor" stroke-width="2.2" />
              <path d="M14 28h6M28 28h6" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
              <circle cx="14" cy="36" r="3" fill="currentColor" />
              <circle cx="34" cy="36" r="3" fill="currentColor" />
              <path d="M24 8v10" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
              <circle cx="24" cy="6" r="3" fill="currentColor" opacity="0.85" />
            </svg>
          </div>
          <h1 class="brand-title">智慧交通视觉感知系统</h1>
          <p class="brand-sub">Smart Traffic Vision Platform</p>
          <p class="brand-desc">
            融合实时监控、场景异常对比、车牌识别与拥堵分析，为沙盘与道路场景提供一体化视觉感知能力。
          </p>
          <ul class="feature-list">
            <li><span class="dot" />实时多路视频监控</li>
            <li><span class="dot" />背景差分异常检测</li>
            <li><span class="dot" />车辆 / 车牌智能识别</li>
          </ul>
        </div>
        <div class="brand-footer">V0.1 · 演示环境</div>
      </aside>

      <!-- 右侧登录区 -->
      <section class="form-panel">
        <div class="form-card">
          <div class="form-head">
            <h2>欢迎登录</h2>
            <p>请使用管理员账号进入系统</p>
          </div>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="请输入用户名" size="large" clearable>
                <template #prefix>
                  <el-icon class="input-icon"><User /></el-icon>
                </template>
              </el-input>
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                placeholder="请输入密码"
                size="large"
                show-password
                @keyup.enter="onSubmit"
              >
                <template #prefix>
                  <el-icon class="input-icon"><Lock /></el-icon>
                </template>
              </el-input>
            </el-form-item>
            <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="onSubmit">
              <span v-if="!loading">进入系统</span>
              <span v-else>登录中…</span>
            </el-button>
          </el-form>

          <p class="form-tip">默认账号：admin · 仅供本地演示使用</p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { login } from '../utils/auth.js'

const router = useRouter()
const route = useRoute()
const formRef = ref(null)
const loading = ref(false)
const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const result = login(form.username, form.password)
    if (!result) {
      ElMessage.error('用户名或密码错误')
      return
    }
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/monitor'
    await router.replace(redirect.startsWith('/') ? redirect : '/monitor')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
  overflow: hidden;
  background: #0b1220;
}

.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(56, 189, 248, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 189, 248, 0.06) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 80% 70% at 50% 50%, #000 20%, transparent 75%);
}

.bg-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
}
.bg-glow-a {
  width: 520px;
  height: 520px;
  top: -120px;
  left: -80px;
  background: rgba(37, 99, 235, 0.35);
}
.bg-glow-b {
  width: 480px;
  height: 480px;
  bottom: -100px;
  right: -60px;
  background: rgba(14, 165, 233, 0.28);
}

.login-shell {
  position: relative;
  z-index: 1;
  display: flex;
  width: 100%;
  max-width: 960px;
  min-height: 560px;
  border-radius: 20px;
  overflow: hidden;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.08),
    0 32px 80px rgba(0, 0, 0, 0.45);
}

/* —— 左侧 —— */
.brand-panel {
  flex: 1.05;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 40px 32px;
  background: linear-gradient(160deg, #0f2744 0%, #0c4a6e 55%, #082f49 100%);
  color: #e2e8f0;
  border-right: 1px solid rgba(255, 255, 255, 0.06);
}

.logo-mark {
  width: 56px;
  height: 56px;
  margin-bottom: 24px;
  color: #38bdf8;
  filter: drop-shadow(0 0 12px rgba(56, 189, 248, 0.45));
}
.logo-mark svg {
  width: 100%;
  height: 100%;
}

.brand-title {
  margin: 0 0 10px;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.35;
  letter-spacing: 0.02em;
  color: #f8fafc;
}

.brand-sub {
  margin: 0 0 20px;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #7dd3fc;
  opacity: 0.9;
}

.brand-desc {
  margin: 0 0 28px;
  font-size: 14px;
  line-height: 1.75;
  color: #94a3b8;
  max-width: 320px;
}

.feature-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.feature-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #cbd5e1;
}
.feature-list .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #38bdf8;
  box-shadow: 0 0 8px #38bdf8;
  flex-shrink: 0;
}

.brand-footer {
  font-size: 12px;
  color: #64748b;
  letter-spacing: 0.06em;
}

/* —— 右侧 —— */
.form-panel {
  flex: 0.95;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 36px;
  background: rgba(255, 255, 255, 0.97);
}

.form-card {
  width: 100%;
  max-width: 340px;
}

.form-head {
  margin-bottom: 32px;
}
.form-head h2 {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
}
.form-head p {
  margin: 0;
  font-size: 14px;
  color: #64748b;
}

.input-icon {
  color: #94a3b8;
  font-size: 16px;
}

:deep(.el-form-item__label) {
  font-weight: 500;
  color: #334155;
  padding-bottom: 6px;
}

:deep(.el-input__wrapper) {
  border-radius: 10px;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
  transition: box-shadow 0.2s;
}
:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #cbd5e1 inset;
}
:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.35) inset;
}

.submit-btn {
  width: 100%;
  margin-top: 12px;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.06em;
  border-radius: 10px;
  background: linear-gradient(135deg, #2563eb 0%, #0ea5e9 100%);
  border: none;
  transition: transform 0.15s, box-shadow 0.15s;
}
.submit-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 24px rgba(37, 99, 235, 0.35);
}

.form-tip {
  margin: 24px 0 0;
  text-align: center;
  font-size: 12px;
  color: #94a3b8;
}

@media (max-width: 768px) {
  .login-shell {
    flex-direction: column;
    max-width: 420px;
    min-height: auto;
  }
  .brand-panel {
    padding: 32px 28px 24px;
    border-right: none;
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  }
  .brand-desc,
  .feature-list {
    display: none;
  }
  .brand-title {
    font-size: 20px;
  }
  .form-panel {
    padding: 32px 28px 36px;
  }
}
</style>
