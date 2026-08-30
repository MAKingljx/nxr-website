import useDictStore from '@/store/modules/dict'
import { getDicts } from '@/api/system/dict/data'
import { tx } from '@/i18n'

const DICT_LABEL_SOURCES = {
  sys_normal_disable: { '0': 'Active', '1': 'Disabled' },
  sys_show_hide: { '0': 'Show', '1': 'Hidden' },
  sys_yes_no: { Y: 'Yes', N: 'No' },
  sys_user_sex: { '0': 'Male', '1': 'Female', '2': 'Unknown' },
  sys_job_group: { DEFAULT: 'Default', SYSTEM: 'System' },
  sys_job_status: { '0': 'Active', '1': 'Paused' },
  sys_common_status: { '0': 'Success', '1': 'Failed' },
  sys_oper_type: {
    '0': 'Other',
    '1': 'Add',
    '2': 'Edit',
    '3': 'Delete',
    '4': 'Authorize',
    '5': 'Export',
    '6': 'Import',
    '7': 'Force Logout',
    '8': 'Generate Code',
    '9': 'Clear Data'
  },
  sys_notice_status: { '0': 'Active', '1': 'Closed' },
  sys_notice_type: { '1': 'Notice', '2': 'Announcement' },
  nxr_product_type: {
    graded_card: 'Graded Card',
    merch_product: 'Merch Product',
    label_product: 'Merch Product',
    vintage_product: 'Vintage Card'
  },
  nxr_card_category: {
    trading_card: 'Trading Card',
    movie_film: 'Movie Film',
    sports_card: 'Sports Card',
    celebrity_card: 'Celebrity Card'
  },
  nxr_language: {
    EN: 'English',
    JP: 'Japanese',
    CT: 'Traditional Chinese',
    CS: 'Simplified Chinese',
    IN: 'Indonesian',
    KO: 'Korean',
    TH: 'Thai',
    Other: 'Other'
  },
  nxr_sports_type: {
    Basketball: 'Basketball',
    Soccer: 'Soccer',
    Baseball: 'Baseball',
    Hockey: 'Hockey',
    Football: 'Football',
    Tennis: 'Tennis'
  }
}

function localizeDictOption(dictType, item) {
  const sourceLabel = DICT_LABEL_SOURCES[dictType]?.[String(item.dictValue)] || item.dictLabel
  return {
    label: tx(sourceLabel),
    value: item.dictValue,
    elTagType: item.listClass,
    elTagClass: item.cssClass
  }
}

/**
 * 获取字典数据
 */
export function useDict(...args) {
  const res = ref({})
  return (() => {
    args.forEach((dictType, index) => {
      res.value[dictType] = []
      const dicts = useDictStore().getDict(dictType)
      if (dicts) {
        res.value[dictType] = dicts
      } else {
        getDicts(dictType).then(resp => {
          res.value[dictType] = resp.data.map((item) => localizeDictOption(dictType, item))
          useDictStore().setDict(dictType, res.value[dictType])
        })
      }
    })
    return toRefs(res.value)
  })()
}
