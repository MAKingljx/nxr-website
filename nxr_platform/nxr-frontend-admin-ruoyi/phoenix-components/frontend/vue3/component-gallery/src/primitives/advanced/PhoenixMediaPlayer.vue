<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string
    mediaType?: 'video' | 'audio'
    title?: string
    poster?: string
    controls?: boolean
    autoplay?: boolean
    muted?: boolean
    loop?: boolean
    preload?: 'none' | 'metadata' | 'auto'
    emptyText?: string
  }>(),
  {
    src: '',
    mediaType: 'video',
    title: '媒体播放器',
    poster: '',
    controls: true,
    autoplay: false,
    muted: false,
    loop: false,
    preload: 'metadata',
    emptyText: '暂无可播放媒体',
  },
)

const emit = defineEmits<{
  play: []
  pause: []
  ended: []
  error: []
  'time-update': [currentTime: number]
}>()

const safeSource = computed(() => {
  const source = props.src.trim()
  if (!source) return ''
  if (/^(?:javascript|vbscript):/i.test(source)) return ''
  if (/^data:/i.test(source) && !/^data:(?:audio|video)\//i.test(source)) return ''
  return source
})

const safeMediaType = computed<'video' | 'audio'>(() => (props.mediaType === 'audio' ? 'audio' : 'video'))

function onTimeUpdate(event: Event) {
  emit('time-update', (event.currentTarget as HTMLMediaElement).currentTime)
}
</script>

<template>
  <div class="px-media-player" :class="`px-media-player--${safeMediaType}`">
    <video
      v-if="safeSource && safeMediaType === 'video'"
      class="px-media-player__media"
      :src="safeSource"
      :poster="poster || undefined"
      :controls="controls"
      :autoplay="autoplay"
      :muted="muted"
      :loop="loop"
      :preload="preload"
      :aria-label="title"
      playsinline
      @play="emit('play')"
      @pause="emit('pause')"
      @ended="emit('ended')"
      @error="emit('error')"
      @timeupdate="onTimeUpdate"
    ></video>
    <audio
      v-else-if="safeSource"
      class="px-media-player__media"
      :src="safeSource"
      :controls="controls"
      :autoplay="autoplay"
      :muted="muted"
      :loop="loop"
      :preload="preload"
      :aria-label="title"
      @play="emit('play')"
      @pause="emit('pause')"
      @ended="emit('ended')"
      @error="emit('error')"
      @timeupdate="onTimeUpdate"
    ></audio>
    <div v-else class="px-advanced-state" role="status">{{ emptyText }}</div>
  </div>
</template>
