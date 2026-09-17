export const addressCountries = [
  { value: 'CN', label: 'China' }, { value: 'US', label: 'United States' },
  { value: 'HK', label: 'Hong Kong' }, { value: 'JP', label: 'Japan' },
  { value: 'GB', label: 'United Kingdom' }, { value: 'AU', label: 'Australia' },
  { value: 'SG', label: 'Singapore' }
]

const regions = {
  CN: [['BJ', 'Beijing'], ['SH', 'Shanghai'], ['GD', 'Guangdong'], ['JS', 'Jiangsu'], ['ZJ', 'Zhejiang'], ['SC', 'Sichuan'], ['FJ', 'Fujian'], ['SD', 'Shandong']],
  US: [['CA', 'California'], ['NY', 'New York'], ['TX', 'Texas'], ['WA', 'Washington'], ['FL', 'Florida'], ['IL', 'Illinois']],
  HK: [['HK', 'Hong Kong']], JP: [['13', 'Tokyo'], ['27', 'Osaka'], ['14', 'Kanagawa']],
  GB: [['ENG', 'England'], ['SCT', 'Scotland'], ['WLS', 'Wales']],
  AU: [['NSW', 'New South Wales'], ['VIC', 'Victoria'], ['QLD', 'Queensland']], SG: [['SG', 'Singapore']]
}
const cities = {
  'CN:BJ': [['Beijing', 'Beijing']], 'CN:SH': [['Shanghai', 'Shanghai']],
  'CN:GD': [['Guangzhou', 'Guangzhou'], ['Shenzhen', 'Shenzhen'], ['Dongguan', 'Dongguan']],
  'CN:JS': [['Nanjing', 'Nanjing'], ['Suzhou', 'Suzhou'], ['Wuxi', 'Wuxi']],
  'CN:ZJ': [['Hangzhou', 'Hangzhou'], ['Ningbo', 'Ningbo']], 'CN:SC': [['Chengdu', 'Chengdu']],
  'CN:FJ': [['Fuzhou', 'Fuzhou'], ['Xiamen', 'Xiamen']], 'CN:SD': [['Jinan', 'Jinan'], ['Qingdao', 'Qingdao']],
  'US:CA': [['Los Angeles', 'Los Angeles'], ['San Francisco', 'San Francisco'], ['San Diego', 'San Diego']],
  'US:NY': [['New York City', 'New York City']], 'US:TX': [['Houston', 'Houston'], ['Dallas', 'Dallas'], ['Austin', 'Austin']],
  'US:WA': [['Seattle', 'Seattle']], 'US:FL': [['Miami', 'Miami'], ['Orlando', 'Orlando']], 'US:IL': [['Chicago', 'Chicago']],
  'HK:HK': [['Hong Kong', 'Hong Kong']], 'JP:13': [['Tokyo', 'Tokyo']], 'JP:27': [['Osaka', 'Osaka']], 'JP:14': [['Yokohama', 'Yokohama']],
  'GB:ENG': [['London', 'London'], ['Manchester', 'Manchester']], 'GB:SCT': [['Edinburgh', 'Edinburgh']], 'GB:WLS': [['Cardiff', 'Cardiff']],
  'AU:NSW': [['Sydney', 'Sydney']], 'AU:VIC': [['Melbourne', 'Melbourne']], 'AU:QLD': [['Brisbane', 'Brisbane']], 'SG:SG': [['Singapore', 'Singapore']]
}
const options = rows => rows.map(([value, label]) => ({ value, label }))
export function addressRegions(country) { return options(regions[country] || []) }
export function addressCities(country, region) { return options(cities[`${country}:${region}`] || []) }
export function withCurrentOption(items, value) {
  const current = String(value || '').trim()
  return current && !items.some(item => item.value === current) ? [{ value: current, label: `${current} (saved)` }, ...items] : items
}
