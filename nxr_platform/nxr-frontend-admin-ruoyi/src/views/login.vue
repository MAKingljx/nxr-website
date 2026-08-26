<template>
  <div class="login-page">
    <div class="login-shell">
      <section class="brand-stage" aria-label="NXR Grading">
        <div class="brand-lockup">
          <img :src="nxrLogo" alt="NXR" class="brand-logo" />
          <div>
            <strong>NXR GRADING</strong>
          </div>
        </div>
      </section>

      <section class="login-panel" aria-labelledby="login-title">
        <header class="login-heading">
          <h2 id="login-title">管理员登录</h2>
        </header>

        <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login-form">
          <div class="field-group">
            <label class="field-label" for="login-username">账号</label>
            <el-form-item prop="username">
              <el-input
                id="login-username"
                v-model="loginForm.username"
                type="text"
                size="large"
                autocomplete="username"
                placeholder="请输入账号"
                clearable
              >
                <template #prefix>
                  <svg-icon icon-class="user" class="input-icon" />
                </template>
              </el-input>
            </el-form-item>
          </div>

          <div class="field-group">
            <label class="field-label" for="login-password">密码</label>
            <el-form-item prop="password">
              <el-input
                id="login-password"
                v-model="loginForm.password"
                type="password"
                size="large"
                autocomplete="current-password"
                placeholder="请输入密码"
                show-password
                @keyup.enter="handleLogin"
              >
                <template #prefix>
                  <svg-icon icon-class="password" class="input-icon" />
                </template>
              </el-input>
            </el-form-item>
          </div>

          <div v-if="captchaEnabled" class="field-group">
            <label class="field-label" for="login-code">验证码</label>
            <el-form-item prop="code">
              <div class="captcha-row">
                <el-input
                  id="login-code"
                  v-model="loginForm.code"
                  size="large"
                  autocomplete="off"
                  placeholder="请输入验证码"
                  @keyup.enter="handleLogin"
                >
                  <template #prefix>
                    <svg-icon icon-class="validCode" class="input-icon" />
                  </template>
                </el-input>
                <button class="captcha-button" type="button" aria-label="刷新验证码" @click="getCode">
                  <img :src="codeUrl" alt="验证码" />
                </button>
              </div>
            </el-form-item>
          </div>

          <div class="form-options">
            <el-checkbox v-model="loginForm.rememberMe">记住密码</el-checkbox>
            <router-link v-if="register" class="register-link" :to="'/register'">立即注册</router-link>
          </div>

          <el-form-item class="submit-item">
            <el-button
              :loading="loading"
              size="large"
              type="primary"
              class="login-button"
              @click.prevent="handleLogin"
            >
              <span>{{ loading ? "登录中..." : "登录" }}</span>
              <el-icon v-if="!loading"><ArrowRight /></el-icon>
            </el-button>
          </el-form-item>
        </el-form>
      </section>
    </div>

    <footer class="login-footer">{{ footerContent }}</footer>
  </div>
</template>

<script setup>
import { getCodeImg } from "@/api/login"
import Cookies from "js-cookie"
import { encrypt, decrypt } from "@/utils/jsencrypt"
import useUserStore from '@/store/modules/user'
import defaultSettings from '@/settings'
import { ArrowRight } from '@element-plus/icons-vue'
import nxrLogo from '@/assets/logo/nxr-logo-circle.png'

const footerContent = defaultSettings.footerContent
const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()

const loginForm = ref({
  username: "admin",
  password: "",
  rememberMe: false,
  code: "",
  uuid: ""
})

const loginRules = {
  username: [{ required: true, trigger: "blur", message: "请输入您的账号" }],
  password: [{ required: true, trigger: "blur", message: "请输入您的密码" }],
  code: [{ required: true, trigger: "change", message: "请输入验证码" }]
}

const codeUrl = ref("")
const loading = ref(false)
// 验证码开关
const captchaEnabled = ref(true)
// 注册开关
const register = ref(false)
const redirect = ref(undefined)

watch(route, (newRoute) => {
    redirect.value = newRoute.query && newRoute.query.redirect
}, { immediate: true })

function handleLogin() {
  proxy.$refs.loginRef.validate(valid => {
    if (valid) {
      loading.value = true
      // 勾选了需要记住密码设置在 cookie 中设置记住用户名和密码
      if (loginForm.value.rememberMe) {
        Cookies.set("username", loginForm.value.username, { expires: 30 })
        Cookies.set("password", encrypt(loginForm.value.password), { expires: 30 })
        Cookies.set("rememberMe", loginForm.value.rememberMe, { expires: 30 })
      } else {
        // 否则移除
        Cookies.remove("username")
        Cookies.remove("password")
        Cookies.remove("rememberMe")
      }
      // 调用action的登录方法
      userStore.login(loginForm.value).then(() => {
        const query = route.query
        const otherQueryParams = Object.keys(query).reduce((acc, cur) => {
          if (cur !== "redirect") {
            acc[cur] = query[cur]
          }
          return acc
        }, {})
        router.push({ path: redirect.value || "/", query: otherQueryParams })
      }).catch(() => {
        loading.value = false
        // 重新获取验证码
        if (captchaEnabled.value) {
          getCode()
        }
      })
    }
  })
}

function getCode() {
  getCodeImg().then(res => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled
    if (captchaEnabled.value) {
      codeUrl.value = "data:image/gif;base64," + res.img
      loginForm.value.uuid = res.uuid
    }
  })
}

function getCookie() {
  const username = Cookies.get("username")
  const password = Cookies.get("password")
  const rememberMe = Cookies.get("rememberMe")
  loginForm.value = {
    username: username === undefined ? loginForm.value.username : username,
    password: password === undefined ? loginForm.value.password : decrypt(password),
    rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
  }
}

getCode()
getCookie()
</script>

<style lang="scss" scoped>
.login-page {
  --auth-panel: rgba(249, 250, 252, 0.96);
  --auth-panel-border: rgba(255, 255, 255, 0.52);
  --auth-text: #171b22;
  --auth-muted: #68707d;
  --auth-border: #d9dee6;
  --auth-field: #ffffff;
  --auth-field-hover: #c3cad5;
  position: relative;
  min-height: 100dvh;
  overflow: hidden;
  background-color: #0b0e14;
  background-image: url("../assets/images/login-background-nxr.webp");
  background-repeat: no-repeat;
  background-position: center;
  background-size: cover;
  color: #ffffff;
}

.login-page::before {
  position: absolute;
  inset: 0;
  background: rgba(5, 8, 13, 0.14);
  content: "";
}

.login-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(300px, 1fr) minmax(360px, 424px);
  align-items: center;
  gap: clamp(48px, 8vw, 128px);
  width: min(1320px, calc(100% - 96px));
  min-height: calc(100dvh - 76px);
  margin: 0 auto;
  padding: 44px 0 28px;
}

.brand-stage {
  align-self: stretch;
  min-width: 0;
  padding: 10px 0;
}

.brand-lockup {
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand-logo {
  width: 54px;
  height: 54px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 50%;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.24);
}

.brand-lockup div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.brand-lockup strong {
  font-size: 17px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0;
}

.login-panel {
  width: 100%;
  padding: 38px 38px 28px;
  border: 1px solid var(--auth-panel-border);
  border-radius: 8px;
  background: var(--auth-panel);
  box-shadow: 0 24px 72px rgba(0, 0, 0, 0.34);
  color: var(--auth-text);
  backdrop-filter: blur(18px);
}

.login-heading {
  margin-bottom: 30px;
}

.login-heading h2 {
  margin: 0;
  color: var(--auth-text);
  font-size: 27px;
  font-weight: 650;
  line-height: 1.25;
  letter-spacing: 0;
}

.login-form {
  width: 100%;
}

.field-group {
  margin-bottom: 19px;
}

.field-label {
  display: block;
  margin-bottom: 8px;
  color: var(--auth-text);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0;
}

.field-group :deep(.el-form-item) {
  margin-bottom: 0;
}

.field-group :deep(.el-form-item__error) {
  padding-top: 5px;
}

.field-group :deep(.el-input__wrapper) {
  min-height: 46px;
  padding: 0 13px;
  border: 1px solid var(--auth-border);
  border-radius: 6px;
  background: var(--auth-field);
  box-shadow: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.field-group :deep(.el-input__wrapper:hover) {
  border-color: var(--auth-field-hover);
}

.field-group :deep(.el-input__wrapper.is-focus) {
  border-color: #2f7db8;
  box-shadow: 0 0 0 3px rgba(47, 125, 184, 0.13);
}

.field-group :deep(.el-input__inner) {
  color: var(--auth-text);
  font-size: 14px;
  letter-spacing: 0;
}

.field-group :deep(.el-input__inner::placeholder) {
  color: #9299a4;
}

.field-group :deep(.el-input__icon) {
  color: #737c89;
}

.input-icon {
  width: 15px;
  height: 15px;
}

.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px;
  gap: 10px;
  width: 100%;
}

.captcha-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 112px;
  height: 46px;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--auth-border);
  border-radius: 6px;
  background: #ffffff;
  cursor: pointer;
}

.captcha-button:focus-visible {
  outline: 3px solid rgba(47, 125, 184, 0.24);
  outline-offset: 2px;
}

.captcha-button img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.form-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 24px;
  margin: 4px 0 24px;
}

.form-options :deep(.el-checkbox__label) {
  color: var(--auth-muted);
  font-size: 13px;
  letter-spacing: 0;
}

.register-link {
  color: #2f6f9f;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}

.submit-item {
  margin-bottom: 0;
}

.login-button {
  width: 100%;
  min-height: 46px;
  border-radius: 6px;
  background: #2f6f9f;
  font-weight: 650;
  letter-spacing: 0;
  box-shadow: 0 10px 22px rgba(47, 111, 159, 0.23);
}

.login-button:hover,
.login-button:focus {
  background: #275f89;
}

.login-button :deep(.el-icon) {
  margin-left: 8px;
}

.login-footer {
  position: absolute;
  z-index: 1;
  right: 0;
  bottom: 20px;
  left: 0;
  padding: 0 24px;
  color: rgba(255, 255, 255, 0.52);
  font-size: 11px;
  line-height: 1.5;
  text-align: center;
  letter-spacing: 0;
}

html.dark .login-page {
  --auth-panel: rgba(23, 25, 30, 0.96);
  --auth-panel-border: rgba(255, 255, 255, 0.11);
  --auth-text: #f3f5f8;
  --auth-muted: #a3a9b3;
  --auth-border: #3b4049;
  --auth-field: #1c1f25;
  --auth-field-hover: #515864;
}

html.dark .field-group :deep(.el-input__inner::placeholder) {
  color: #767e8a;
}

html.dark .captcha-button {
  background: #ffffff;
}

@media (max-width: 900px) {
  .login-page {
    overflow-y: auto;
    background-position: 35% center;
  }

  .login-page::before {
    background: rgba(5, 8, 13, 0.5);
  }

  .login-shell {
    grid-template-columns: 1fr;
    gap: 32px;
    width: min(520px, calc(100% - 32px));
    min-height: auto;
    padding: 28px 0 88px;
  }

  .brand-stage {
    padding: 0;
  }

  .login-footer {
    bottom: 16px;
  }
}

@media (max-width: 520px) {
  .login-page {
    background-position: 28% center;
  }

  .login-page::before {
    background: rgba(5, 8, 13, 0.66);
  }

  .login-shell {
    gap: 24px;
    width: calc(100% - 24px);
    padding-top: 18px;
  }

  .brand-stage {
    padding: 0;
  }

  .brand-logo {
    width: 46px;
    height: 46px;
  }

  .login-panel {
    padding: 28px 22px 23px;
  }

  .login-heading {
    margin-bottom: 24px;
  }

  .login-heading h2 {
    font-size: 24px;
  }

  .captcha-row {
    grid-template-columns: minmax(0, 1fr) 100px;
    gap: 8px;
  }

  .captcha-button {
    width: 100px;
  }
}

@media (max-height: 720px) and (min-width: 901px) {
  .login-shell {
    min-height: calc(100dvh - 52px);
    padding-top: 24px;
  }

  .login-panel {
    padding-top: 30px;
    padding-bottom: 24px;
  }

  .login-heading {
    margin-bottom: 22px;
  }

  .field-group {
    margin-bottom: 14px;
  }

  .form-options {
    margin-bottom: 18px;
  }
}
</style>
