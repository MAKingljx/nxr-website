import { inject, type ComputedRef, type InjectionKey } from 'vue'

export type PhoenixTheme = 'modern' | 'business' | 'minimal' | 'festive'

export interface PhoenixThemeContext {
  theme: ComputedRef<PhoenixTheme>
}

export const PHOENIX_THEME_KEY: InjectionKey<PhoenixThemeContext> = Symbol('phoenix-theme')

export function usePhoenixTheme() {
  return inject(PHOENIX_THEME_KEY)
}
