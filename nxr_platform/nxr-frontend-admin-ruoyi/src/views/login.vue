<template>
  <div class="login-page">
    <div class="login-shell">
      <section class="brand-stage" :aria-label="$tx('NXR Grading')">
        <div class="brand-lockup">
          <img :src="nxrLogo" :alt="$tx('NXR')" class="brand-logo" />
          <div>
            <strong>{{ $tx('NXR GRADING') }}</strong>
          </div>
        </div>
      </section>

      <section class="login-panel" aria-labelledby="login-title">
        <header class="login-heading">
          <h2 id="login-title">{{ $tx('Administrator Sign In') }}</h2>
        </header>

        <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login-form">
          <div class="field-group">
            <label class="field-label" for="login-username">{{ $tx('Username') }}</label>
            <el-form-item prop="username">
              <el-input
                id="login-username"
                v-model="loginForm.username"
                type="text"
                size="large"
                autocomplete="username"
                :placeholder="$tx('Enter your username')"
                clearable
              >
                <template #prefix>
                  <svg-icon icon-class="user" class="input-icon" />
                </template>
              </el-input>
            </el-form-item>
          </div>

          <div class="field-group">
            <label class="field-label" for="login-password">{{ $tx('Password') }}</label>
            <el-form-item prop="password">
              <el-input
                id="login-password"
                v-model="loginForm.password"
                type="password"
                size="large"
                autocomplete="current-password"
                :placeholder="$tx('Enter your password')"
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
            <label class="field-label" for="login-code">{{ $tx('Verification Code') }}</label>
            <el-form-item prop="code">
              <div class="captcha-row">
                <el-input
                  id="login-code"
                  v-model="loginForm.code"
                  size="large"
                  autocomplete="off"
                  :placeholder="$tx('Enter the verification code')"
                  @keyup.enter="handleLogin"
                >
                  <template #prefix>
                    <svg-icon icon-class="validCode" class="input-icon" />
                  </template>
                </el-input>
                <button class="captcha-button" type="button" :aria-label="$tx('Refresh verification code')" @click="getCode">
                  <img :src="codeUrl" :alt="$tx('Verification code')" />
                </button>
              </div>
            </el-form-item>
          </div>

          <div class="form-options">
            <el-checkbox v-model="loginForm.rememberMe">{{ $tx('Keep me signed in') }}</el-checkbox>
            <router-link v-if="register" class="register-link" :to="'/register'">{{ $tx('Create account') }}</router-link>
          </div>

          <el-form-item class="submit-item">
            <el-button
              :loading="loading"
              size="large"
              type="primary"
              class="login-button"
              @click.prevent="handleLogin"
            >
              <span>{{ loading ? "Signing in..." : "Sign In" }}</span>
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
  username: [{ required: true, trigger: "blur", message: tx('Enter your username') }],
  password: [{ required: true, trigger: "blur", message: tx('Enter your password') }],
  code: [{ required: true, trigger: "change", message: tx('Enter the verification code') }]
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
      // Keep the existing convenience fields, while the server-issued token controls session persistence.
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
    rememberMe: rememberMe === undefined ? false : rememberMe === 'true'
  }
}

getCode()
getCookie()
</script>

<style lang="scss" scoped>
.login-page {
  --auth-panel: rgba(255, 255, 255, 0.48);
  --auth-panel-border: rgba(255, 255, 255, 0.72);
  --auth-text: #17233d;
  --auth-muted: #53627c;
  --auth-border: rgba(255, 255, 255, 0.68);
  --auth-field: rgba(255, 255, 255, 0.46);
  --auth-field-hover: rgba(255, 255, 255, 0.92);
  position: relative;
  min-height: 100dvh;
  overflow: hidden;
  background:
    radial-gradient(circle at 8% 8%, rgba(255, 255, 255, 0.82) 0, rgba(255, 255, 255, 0) 31%),
    radial-gradient(circle at 88% 16%, rgba(255, 179, 218, 0.72) 0, rgba(255, 179, 218, 0) 35%),
    radial-gradient(circle at 78% 88%, rgba(173, 208, 255, 0.78) 0, rgba(173, 208, 255, 0) 42%),
    linear-gradient(135deg, #8fd8ff 0%, #bcb9ff 47%, #ffd1e5 100%);
  color: #17233d;
}

.login-shell {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: min(1320px, calc(100% - 96px));
  min-height: calc(100dvh - 76px);
  margin: 0 auto;
  padding: 44px 0 28px;
}

.brand-stage {
  position: absolute;
  top: 44px;
  left: 0;
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
  color: #17233d;
  font-size: 17px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0;
}

.login-panel {
  width: 100%;
  max-width: 424px;
  padding: 38px 38px 28px;
  border: 1px solid var(--auth-panel-border);
  border-radius: 18px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.66), rgba(255, 255, 255, 0.32));
  box-shadow: 0 24px 72px rgba(72, 58, 119, 0.24), inset 0 1px 0 rgba(255, 255, 255, 0.78);
  color: var(--auth-text);
  backdrop-filter: blur(24px) saturate(145%);
  -webkit-backdrop-filter: blur(24px) saturate(145%);
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
  background: linear-gradient(135deg, #4e83d7, #7968d7);
  font-weight: 650;
  letter-spacing: 0;
  box-shadow: 0 10px 22px rgba(47, 111, 159, 0.23);
}

.login-button:hover,
.login-button:focus {
  background: linear-gradient(135deg, #416fb9, #6857bf);
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
  color: rgba(34, 48, 82, 0.64);
  font-size: 11px;
  line-height: 1.5;
  text-align: center;
  letter-spacing: 0;
}

html.dark .captcha-button {
  background: #ffffff;
}

@media (max-width: 900px) {
  .login-page {
    overflow-y: auto;
  }

  .login-shell {
    flex-direction: column;
    gap: 32px;
    width: min(520px, calc(100% - 32px));
    min-height: auto;
    padding: 28px 0 88px;
  }

  .brand-stage {
    position: static;
    width: 100%;
    padding: 0;
  }

  .login-footer {
    bottom: 16px;
  }
}

@media (max-width: 520px) {
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
