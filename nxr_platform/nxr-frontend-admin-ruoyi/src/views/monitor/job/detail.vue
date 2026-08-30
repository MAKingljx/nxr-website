<template>
  <el-dialog :title="type === 'log' ? $tx('Job Log Details') : $tx('Job Details')" v-model="dialogVisible" width="780px" append-to-body>
    <div class="detail-wrap">
      <template v-if="type === 'log'">
        <!-- 基本信息 -->
        <div class="detail-card">
          <div class="detail-card-title">
            <el-icon><InfoFilled /></el-icon> {{ $tx('General') }} </div>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Log ID') }}</span><span class="detail-value">{{ form.jobLogId }}</span></div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <span class="detail-label">{{ $tx('Status') }}</span>
                <el-tag v-if="form.status == 0" type="success" size="small">{{ $tx('Success') }}</el-tag>
                <el-tag v-else type="danger" size="small">{{ $tx('Failed') }}</el-tag>
              </div>
            </el-col>
          </el-row>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Started At') }}</span><span class="detail-value">{{ form.startTime }}</span></div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Finished At') }}</span><span class="detail-value">{{ form.endTime }}</span></div>
            </el-col>
          </el-row>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Recorded At') }}</span><span class="detail-value">{{ form.createTime }}</span></div>
            </el-col>
            <el-col :span="12" v-if="form.status == 0 && form.startTime && form.endTime">
              <div class="detail-item"><span class="detail-label">{{ $tx('Duration') }}</span><span class="detail-value">{{ costTime }} {{ $tx('ms') }}</span></div>
            </el-col>
          </el-row>
        </div>
        <!-- 任务信息 -->
        <div class="detail-card">
          <div class="detail-card-title">
            <el-icon><Clock /></el-icon> {{ $tx('Job') }} </div>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Job Name') }}</span><span class="detail-value">{{ form.jobName }}</span></div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <span class="detail-label">{{ $tx('Job Group') }}</span>
                <dict-tag :options="sys_job_group" :value="form.jobGroup" />
              </div>
            </el-col>
          </el-row>
          <el-row class="detail-row">
            <el-col :span="24">
              <div class="detail-item"><span class="detail-label">{{ $tx('Message') }}</span><span class="detail-value">{{ form.jobMessage }}</span></div>
            </el-col>
          </el-row>
        </div>
        <!-- 调用目标 -->
        <div class="detail-card">
          <div class="detail-card-title">
            <el-icon><Operation /></el-icon> {{ $tx('Invocation Target') }} </div>
          <div class="code-body">
            <div class="code-wrap"><pre class="code-pre">{{ form.invokeTarget || '(None)' }}</pre></div>
          </div>
        </div>
        <!-- 异常信息 -->
        <div class="detail-card" v-if="form.status == 1">
          <div class="detail-card-title error-title">
            <el-icon><Warning /></el-icon> {{ $tx('Error Details') }} </div>
          <div class="error-body"><div class="error-msg">{{ form.exceptionInfo }}</div></div>
        </div>
      </template>

      <template v-else>
        <!-- 任务配置 -->
        <div class="detail-card">
          <div class="detail-card-title">
            <el-icon><Setting /></el-icon> {{ $tx('Job Configuration') }} </div>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Job ID') }}</span><span class="detail-value">{{ form.jobId }}</span></div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Job Name') }}</span><span class="detail-value">{{ form.jobName }}</span></div>
            </el-col>
          </el-row>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item">
                <span class="detail-label">{{ $tx('Job Group') }}</span>
                <dict-tag :options="sys_job_group" :value="form.jobGroup" />
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <span class="detail-label">{{ $tx('Status') }}</span>
                <el-tag v-if="form.status == 0" type="success" size="small">{{ $tx('Active') }}</el-tag>
                <el-tag v-else type="info" size="small">{{ $tx('Paused') }}</el-tag>
              </div>
            </el-col>
          </el-row>
        </div>
        <!-- 调度信息 -->
        <div class="detail-card">
          <div class="detail-card-title">
            <el-icon><Calendar /></el-icon> {{ $tx('Schedule') }} </div>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Cron Expression') }}</span><span class="detail-value mono">{{ form.cronExpression }}</span></div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Next Run') }}</span><span class="detail-value">{{ parseTime(form.nextValidTime) }}</span></div>
            </el-col>
          </el-row>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item">
                <span class="detail-label">{{ $tx('Misfire Policy') }}</span>
                <el-tag v-if="form.misfirePolicy == 0" type="info" size="small">{{ $tx('Default') }}</el-tag>
                <el-tag v-else-if="form.misfirePolicy == 1" type="warning" size="small">{{ $tx('Run Immediately') }}</el-tag>
                <el-tag v-else-if="form.misfirePolicy == 2" type="primary" size="small">{{ $tx('Run Once') }}</el-tag>
                <el-tag v-else-if="form.misfirePolicy == 3" type="danger" size="small">{{ $tx('Skip') }}</el-tag>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <span class="detail-label">{{ $tx('Concurrent Runs') }}</span>
                <el-tag v-if="form.concurrent == 0" type="success" size="small">{{ $tx('Allowed') }}</el-tag>
                <el-tag v-else type="danger" size="small">{{ $tx('Blocked') }}</el-tag>
              </div>
            </el-col>
          </el-row>
        </div>
        <!-- 执行方法 -->
        <div class="detail-card">
          <div class="detail-card-title">
            <el-icon><Operation /></el-icon> {{ $tx('Invocation Target') }} </div>
          <div class="code-body">
            <div class="code-wrap"><pre class="code-pre">{{ form.invokeTarget || '(None)' }}</pre></div>
          </div>
        </div>
        <!-- 元信息 -->
        <div class="detail-card">
          <div class="detail-card-title">
            <el-icon><Document /></el-icon> {{ $tx('Metadata') }} </div>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Created By') }}</span><span class="detail-value">{{ form.createBy || '-' }}</span></div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Created At') }}</span><span class="detail-value">{{ form.createTime }}</span></div>
            </el-col>
          </el-row>
          <el-row class="detail-row">
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Updated By') }}</span><span class="detail-value">{{ form.updateBy || '-' }}</span></div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item"><span class="detail-label">{{ $tx('Updated At') }}</span><span class="detail-value">{{ form.updateTime || '-' }}</span></div>
            </el-col>
          </el-row>
          <el-row class="detail-row" v-if="form.remark">
            <el-col :span="24">
              <div class="detail-item"><span class="detail-label">{{ $tx('Notes') }}</span><span class="detail-value">{{ form.remark }}</span></div>
            </el-col>
          </el-row>
        </div>
      </template>
    </div>
    <template #footer>
      <div class="dialog-footer">
        <el-button @click="dialogVisible = false">{{ $tx('Close') }}</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup name="JobDetail">
const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: () => ({}) },
  // 'job' 任务详细 | 'log' 调度日志详细
  type: { type: String, default: 'job' }
})

const emit = defineEmits(['update:visible'])

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const { proxy } = getCurrentInstance()
const { sys_job_group } = useDict('sys_job_group')

const form = computed(() => props.row || {})

const costTime = computed(() => {
  if (!form.value.startTime || !form.value.endTime) return 0
  return new Date(form.value.endTime).getTime() - new Date(form.value.startTime).getTime()
})
</script>

<style scoped>
.detail-label {
  width: 80px;
}
</style>
