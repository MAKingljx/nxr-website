export type AddressOption = { value: string; label: string }

// Keep the selector useful without making addresses depend on a remote service.
// Unknown countries/regions remain editable as plain text so existing saved
// addresses and less common destinations are never blocked.
export const addressCountries: AddressOption[] = [
  { value: 'CN', label: 'China' },
  { value: 'US', label: 'United States' },
  { value: 'HK', label: 'Hong Kong' },
  { value: 'JP', label: 'Japan' },
  { value: 'GB', label: 'United Kingdom' },
  { value: 'AU', label: 'Australia' },
  { value: 'SG', label: 'Singapore' },
]

const regions: Record<string, AddressOption[]> = {
  CN: [
    ['BJ', 'Beijing'], ['SH', 'Shanghai'], ['GD', 'Guangdong'], ['JS', 'Jiangsu'],
    ['ZJ', 'Zhejiang'], ['SC', 'Sichuan'], ['FJ', 'Fujian'], ['SD', 'Shandong'],
  ].map(([value, label]) => ({ value, label })),
  US: [
    ['CA', 'California'], ['NY', 'New York'], ['TX', 'Texas'], ['WA', 'Washington'],
    ['FL', 'Florida'], ['IL', 'Illinois'],
  ].map(([value, label]) => ({ value, label })),
  HK: [{ value: 'HK', label: 'Hong Kong' }],
  JP: [['13', 'Tokyo'], ['27', 'Osaka'], ['14', 'Kanagawa']].map(([value, label]) => ({ value, label })),
  GB: [['ENG', 'England'], ['SCT', 'Scotland'], ['WLS', 'Wales']].map(([value, label]) => ({ value, label })),
  AU: [['NSW', 'New South Wales'], ['VIC', 'Victoria'], ['QLD', 'Queensland']].map(([value, label]) => ({ value, label })),
  SG: [{ value: 'SG', label: 'Singapore' }],
}

const cities: Record<string, AddressOption[]> = {
  'CN:BJ': [{ value: 'Beijing', label: 'Beijing' }],
  'CN:SH': [{ value: 'Shanghai', label: 'Shanghai' }],
  'CN:GD': [['Guangzhou', 'Guangzhou'], ['Shenzhen', 'Shenzhen'], ['Dongguan', 'Dongguan']].map(([value, label]) => ({ value, label })),
  'CN:JS': [['Nanjing', 'Nanjing'], ['Suzhou', 'Suzhou'], ['Wuxi', 'Wuxi']].map(([value, label]) => ({ value, label })),
  'CN:ZJ': [['Hangzhou', 'Hangzhou'], ['Ningbo', 'Ningbo']].map(([value, label]) => ({ value, label })),
  'CN:SC': [{ value: 'Chengdu', label: 'Chengdu' }],
  'CN:FJ': [['Fuzhou', 'Fuzhou'], ['Xiamen', 'Xiamen']].map(([value, label]) => ({ value, label })),
  'CN:SD': [['Jinan', 'Jinan'], ['Qingdao', 'Qingdao']].map(([value, label]) => ({ value, label })),
  'US:CA': [['Los Angeles', 'Los Angeles'], ['San Francisco', 'San Francisco'], ['San Diego', 'San Diego']].map(([value, label]) => ({ value, label })),
  'US:NY': [{ value: 'New York City', label: 'New York City' }],
  'US:TX': [['Houston', 'Houston'], ['Dallas', 'Dallas'], ['Austin', 'Austin']].map(([value, label]) => ({ value, label })),
  'US:WA': [{ value: 'Seattle', label: 'Seattle' }],
  'US:FL': [['Miami', 'Miami'], ['Orlando', 'Orlando']].map(([value, label]) => ({ value, label })),
  'US:IL': [{ value: 'Chicago', label: 'Chicago' }],
  'HK:HK': [{ value: 'Hong Kong', label: 'Hong Kong' }],
  'JP:13': [{ value: 'Tokyo', label: 'Tokyo' }],
  'JP:27': [{ value: 'Osaka', label: 'Osaka' }],
  'JP:14': [{ value: 'Yokohama', label: 'Yokohama' }],
  'GB:ENG': [['London', 'London'], ['Manchester', 'Manchester']].map(([value, label]) => ({ value, label })),
  'GB:SCT': [{ value: 'Edinburgh', label: 'Edinburgh' }],
  'GB:WLS': [{ value: 'Cardiff', label: 'Cardiff' }],
  'AU:NSW': [{ value: 'Sydney', label: 'Sydney' }],
  'AU:VIC': [{ value: 'Melbourne', label: 'Melbourne' }],
  'AU:QLD': [{ value: 'Brisbane', label: 'Brisbane' }],
  'SG:SG': [{ value: 'Singapore', label: 'Singapore' }],
}

export function addressRegions(country: string): AddressOption[] {
  return regions[country] || []
}

export function addressCities(country: string, region: string): AddressOption[] {
  return cities[`${country}:${region}`] || []
}

export function withCurrentOption(options: AddressOption[], value: string | null | undefined): AddressOption[] {
  const current = String(value || '').trim()
  if (!current || options.some(option => option.value === current)) return options
  return [{ value: current, label: `${current} (saved)` }, ...options]
}
