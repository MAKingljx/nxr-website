export type PhoenixAnalyticsAppearance = 'modern' | 'minimal' | 'soft'

export type PhoenixAnalyticsValueKind = 'number' | 'currency' | 'percent'

export interface PhoenixChartDatum {
  key: string
  label: string
  value: number
  color?: string
  description?: string
  disabled?: boolean
}

export interface PhoenixLinePoint {
  key: string
  label: string
  value: number
}

export interface PhoenixLineSeries {
  key: string
  label: string
  color?: string
  points: PhoenixLinePoint[]
}

export interface PhoenixLegendItem {
  key: string
  label: string
  color?: string
  value?: number | string
  disabled?: boolean
}

export interface PhoenixDashboardFilterOption {
  label: string
  value: string
  disabled?: boolean
}

export interface PhoenixDashboardFilterItem {
  key: string
  label: string
  options: PhoenixDashboardFilterOption[]
  placeholder?: string
  disabled?: boolean
}

export type PhoenixDashboardFilterValue = Record<string, string>
