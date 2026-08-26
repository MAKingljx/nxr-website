<script setup lang="ts">
import { computed } from 'vue'
import {
  PhoenixActivityFeed,
  PhoenixAnnouncementBanner,
  PhoenixMessageInbox,
  PhoenixNotificationCenter,
} from '../../../primitives/content'
import type {
  PhoenixActivityItem,
  PhoenixAnnouncementTone,
  PhoenixContentAppearance,
  PhoenixInboxFolder,
  PhoenixMessageThread,
  PhoenixNotificationFilter,
  PhoenixNotificationItem,
} from '../../../primitives/content'
import '../../../content-primitives.css'

export type PhoenixContentWorkspacePanel = 'inbox' | 'notifications' | 'activity'

export interface PhoenixContentWorkspaceAnnouncement {
  visible?: boolean
  title?: string
  description?: string
  label?: string
  actionLabel?: string
  dismissible?: boolean
  tone?: PhoenixAnnouncementTone
}

export type PhoenixContentWorkspaceSelection =
  | { kind: 'notification'; item: PhoenixNotificationItem }
  | { kind: 'thread'; item: PhoenixMessageThread }
  | { kind: 'activity'; item: PhoenixActivityItem }

export type PhoenixContentWorkspaceAction =
  | { kind: 'notification'; item: PhoenixNotificationItem }
  | { kind: 'activity'; item: PhoenixActivityItem }
  | { kind: 'announcement'; item: PhoenixContentWorkspaceAnnouncement }

export type PhoenixContentWorkspaceDismissal =
  | { kind: 'notification'; item: PhoenixNotificationItem }
  | { kind: 'announcement'; item: PhoenixContentWorkspaceAnnouncement }

const props = withDefaults(defineProps<{
  notifications?: PhoenixNotificationItem[]
  threads?: PhoenixMessageThread[]
  activities?: PhoenixActivityItem[]
  announcement?: PhoenixContentWorkspaceAnnouncement
  notificationFilter?: PhoenixNotificationFilter
  selectedThreadId?: string | number
  messageQuery?: string
  inboxFolder?: PhoenixInboxFolder
  activePanel?: PhoenixContentWorkspacePanel
  appearance?: PhoenixContentAppearance
  title?: string
  subtitle?: string
  loading?: boolean
  notificationLoading?: boolean
  inboxLoading?: boolean
  activityLoading?: boolean
  activityHasMore?: boolean
  disabled?: boolean
}>(), {
  notifications: () => [],
  threads: () => [],
  activities: () => [],
  announcement: undefined,
  notificationFilter: 'all',
  selectedThreadId: undefined,
  messageQuery: '',
  inboxFolder: 'inbox',
  activePanel: 'inbox',
  appearance: 'modern',
  title: '内容工作台',
  subtitle: '集中处理消息、通知与团队动态',
  loading: false,
  notificationLoading: false,
  inboxLoading: false,
  activityLoading: false,
  activityHasMore: false,
  disabled: false,
})

const emit = defineEmits<{
  'update:notificationFilter': [filter: PhoenixNotificationFilter]
  'update:selectedThreadId': [id: string | number]
  'update:messageQuery': [query: string]
  'update:inboxFolder': [folder: PhoenixInboxFolder]
  'update:activePanel': [panel: PhoenixContentWorkspacePanel]
  'update:announcementVisible': [visible: boolean]
  select: [selection: PhoenixContentWorkspaceSelection]
  read: [notification: PhoenixNotificationItem]
  'read-all': []
  compose: []
  archive: [thread: PhoenixMessageThread]
  star: [thread: PhoenixMessageThread, starred: boolean]
  action: [action: PhoenixContentWorkspaceAction]
  dismiss: [dismissal: PhoenixContentWorkspaceDismissal]
  'load-more': []
}>()

const appearanceValue = computed<PhoenixContentAppearance>(() => props.appearance === 'minimal' || props.appearance === 'soft' ? props.appearance : 'modern')
const unreadNotifications = computed(() => props.notifications.filter((item) => !item.read).length)
const unreadThreads = computed(() => props.threads.filter((item) => item.unread && !item.archived).length)
const announcementVisible = computed(() => Boolean(props.announcement && props.announcement.visible !== false))

function selectNotification(item: PhoenixNotificationItem) {
  emit('select', { kind: 'notification', item })
}

function selectThread(item: PhoenixMessageThread) {
  emit('select', { kind: 'thread', item })
}

function selectActivity(item: PhoenixActivityItem) {
  emit('select', { kind: 'activity', item })
}

function notificationAction(item: PhoenixNotificationItem) {
  emit('action', { kind: 'notification', item })
}

function activityAction(item: PhoenixActivityItem) {
  emit('action', { kind: 'activity', item })
}

function announcementAction() {
  if (props.announcement) emit('action', { kind: 'announcement', item: props.announcement })
}

function dismissNotification(item: PhoenixNotificationItem) {
  emit('dismiss', { kind: 'notification', item })
}

function dismissAnnouncement() {
  if (props.announcement) emit('dismiss', { kind: 'announcement', item: props.announcement })
}
</script>

<template>
  <main class="px-content-workspace" :data-appearance="appearanceValue" :data-active-panel="activePanel" :aria-busy="loading">
    <header class="px-content-workspace__hero">
      <div class="px-content-workspace__heading">
        <span class="px-content-workspace__eyebrow">CONTENT HUB</span>
        <h1>{{ title }}</h1>
        <p>{{ subtitle }}</p>
      </div>
      <dl class="px-content-workspace__stats" aria-label="内容概览">
        <div><dt>未读消息</dt><dd>{{ unreadThreads }}</dd></div>
        <div><dt>未读通知</dt><dd>{{ unreadNotifications }}</dd></div>
        <div><dt>近期动态</dt><dd>{{ activities.length }}</dd></div>
      </dl>
      <button class="px-content-workspace__compose" type="button" :disabled="disabled || loading" @click="emit('compose')">写消息</button>
    </header>

    <PhoenixAnnouncementBanner
      v-if="announcementVisible && announcement"
      class="px-content-workspace__announcement"
      :appearance="appearanceValue"
      :visible="announcement.visible !== false"
      :title="announcement.title"
      :description="announcement.description"
      :label="announcement.label"
      :action-label="announcement.actionLabel"
      :dismissible="announcement.dismissible"
      :tone="announcement.tone"
      @update:visible="emit('update:announcementVisible', $event)"
      @action="announcementAction"
      @dismiss="dismissAnnouncement"
    />

    <nav class="px-content-workspace__switcher" aria-label="内容工作台视图">
      <button type="button" :aria-pressed="activePanel === 'inbox'" @click="emit('update:activePanel', 'inbox')">消息 <span>{{ unreadThreads }}</span></button>
      <button type="button" :aria-pressed="activePanel === 'notifications'" @click="emit('update:activePanel', 'notifications')">通知 <span>{{ unreadNotifications }}</span></button>
      <button type="button" :aria-pressed="activePanel === 'activity'" @click="emit('update:activePanel', 'activity')">动态 <span>{{ activities.length }}</span></button>
    </nav>

    <div class="px-content-workspace__grid">
      <section class="px-content-workspace__panel px-content-workspace__panel--inbox" :class="{ 'is-active': activePanel === 'inbox' }" aria-label="消息区域">
        <PhoenixMessageInbox
          :threads="threads"
          :selected-id="selectedThreadId"
          :query="messageQuery"
          :folder="inboxFolder"
          :appearance="appearanceValue"
          title="消息"
          :loading="loading || inboxLoading"
          :disabled="disabled"
          @update:selected-id="emit('update:selectedThreadId', $event)"
          @update:query="emit('update:messageQuery', $event)"
          @update:folder="emit('update:inboxFolder', $event)"
          @select="selectThread"
          @compose="emit('compose')"
          @archive="emit('archive', $event)"
          @star="(thread, starred) => emit('star', thread, starred)"
        />
      </section>

      <section class="px-content-workspace__panel px-content-workspace__panel--notifications" :class="{ 'is-active': activePanel === 'notifications' }" aria-label="通知区域">
        <PhoenixNotificationCenter
          :items="notifications"
          :filter="notificationFilter"
          :appearance="appearanceValue"
          title="通知"
          :loading="loading || notificationLoading"
          :disabled="disabled"
          @update:filter="emit('update:notificationFilter', $event)"
          @select="selectNotification"
          @mark-read="emit('read', $event)"
          @mark-all-read="emit('read-all')"
          @dismiss="dismissNotification"
          @action="notificationAction"
        />
      </section>

      <section class="px-content-workspace__panel px-content-workspace__panel--activity" :class="{ 'is-active': activePanel === 'activity' }" aria-label="动态区域">
        <PhoenixActivityFeed
          :items="activities"
          :appearance="appearanceValue"
          title="团队动态"
          :loading="loading || activityLoading"
          :disabled="disabled"
          :has-more="activityHasMore"
          @select="selectActivity"
          @action="activityAction"
          @load-more="emit('load-more')"
        />
      </section>
    </div>
  </main>
</template>

<style scoped>
.px-content-workspace {
  --workspace-primary: var(--px-primary, #635bff);
  --workspace-ink: var(--px-ink, #1b2032);
  --workspace-muted: var(--px-muted, #70778c);
  --workspace-border: var(--px-border, #e2e5ee);
  --workspace-surface: var(--px-surface, #fff);
  --workspace-soft: var(--px-soft, #f5f6fa);
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  border-radius: 24px;
  padding: clamp(14px, 2.4vw, 28px);
  color: var(--workspace-ink);
  background:
    radial-gradient(circle at 88% 4%, rgb(99 91 255 / 13%), transparent 28%),
    linear-gradient(145deg, #f7f8fc, #f1f2f8);
  font-family: var(--px-font, Inter, "PingFang SC", "Microsoft YaHei", ui-sans-serif, system-ui, sans-serif);
}

.px-content-workspace[data-appearance="minimal"] { border-radius: 8px; padding: clamp(8px, 1.8vw, 20px); background: var(--workspace-surface); }
.px-content-workspace[data-appearance="soft"] { background: var(--workspace-soft); }
.px-content-workspace * { box-sizing: border-box; }

.px-content-workspace__hero {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto auto;
  align-items: center;
  gap: clamp(16px, 3vw, 34px);
  margin-bottom: 18px;
  border: 1px solid rgb(255 255 255 / 72%);
  border-radius: 20px;
  padding: clamp(17px, 2vw, 24px);
  background: rgb(255 255 255 / 82%);
  box-shadow: 0 16px 42px rgb(38 43 72 / 8%);
  backdrop-filter: blur(12px);
}

[data-appearance="minimal"] .px-content-workspace__hero { border-color: var(--workspace-border); border-width: 0 0 1px; border-radius: 0; padding-inline: 0; box-shadow: none; backdrop-filter: none; }
[data-appearance="soft"] .px-content-workspace__hero { border-color: transparent; box-shadow: none; }

.px-content-workspace__eyebrow { display: block; margin-bottom: 5px; color: var(--workspace-primary); font-size: 10px; font-weight: 850; letter-spacing: .16em; }
.px-content-workspace__heading h1 { margin: 0; font-size: clamp(22px, 3vw, 32px); letter-spacing: -.04em; line-height: 1.2; }
.px-content-workspace__heading p { margin: 7px 0 0; color: var(--workspace-muted); font-size: 13px; line-height: 1.55; }

.px-content-workspace__stats { display: flex; align-items: stretch; gap: 8px; margin: 0; }
.px-content-workspace__stats > div { min-width: 78px; border-radius: 13px; padding: 9px 11px; background: var(--workspace-soft); text-align: center; }
.px-content-workspace__stats dt { color: var(--workspace-muted); font-size: 10px; white-space: nowrap; }
.px-content-workspace__stats dd { margin: 4px 0 0; font-size: 20px; font-weight: 800; }

.px-content-workspace__compose,
.px-content-workspace__switcher button {
  min-height: 40px;
  border: 0;
  border-radius: 11px;
  padding: 0 15px;
  font: 700 12px var(--px-font, sans-serif);
  cursor: pointer;
}

.px-content-workspace__compose { color: #fff; background: linear-gradient(135deg, #736cff, #5148e3); box-shadow: 0 9px 22px rgb(99 91 255 / 24%); }
.px-content-workspace__compose:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 12px 26px rgb(99 91 255 / 30%); }
.px-content-workspace__compose:disabled { opacity: .45; cursor: not-allowed; }
.px-content-workspace__compose:focus-visible,
.px-content-workspace__switcher button:focus-visible { outline: 3px solid rgb(99 91 255 / 24%); outline-offset: 2px; }

.px-content-workspace__announcement { margin-bottom: 18px; }
.px-content-workspace__switcher { display: none; }
.px-content-workspace__grid { display: grid; min-width: 0; grid-template-columns: minmax(0, 1.12fr) minmax(320px, .88fr); align-items: start; gap: 18px; }
.px-content-workspace__panel { min-width: 0; }
.px-content-workspace__panel--activity { grid-column: 1 / -1; }

@media (max-width: 980px) {
  .px-content-workspace__hero { grid-template-columns: minmax(0, 1fr) auto; }
  .px-content-workspace__stats { grid-column: 1; grid-row: 2; justify-self: start; }
  .px-content-workspace__compose { grid-column: 2; grid-row: 1 / 3; }
  .px-content-workspace__grid { grid-template-columns: minmax(0, 1fr) minmax(290px, .8fr); gap: 14px; }
}

@media (max-width: 760px) {
  .px-content-workspace { border-radius: 16px; padding: 12px; }
  .px-content-workspace__hero { grid-template-columns: minmax(0, 1fr) auto; margin-bottom: 12px; border-radius: 15px; padding: 15px; }
  .px-content-workspace__stats { grid-column: 1 / -1; width: 100%; overflow-x: auto; }
  .px-content-workspace__stats > div { flex: 1; }
  .px-content-workspace__compose { grid-column: 2; grid-row: 1; }
  .px-content-workspace__switcher { display: flex; gap: 4px; margin-bottom: 12px; border-radius: 12px; padding: 4px; background: rgb(255 255 255 / 82%); }
  .px-content-workspace__switcher button { display: flex; flex: 1; align-items: center; justify-content: center; gap: 5px; color: var(--workspace-muted); background: transparent; }
  .px-content-workspace__switcher button[aria-pressed="true"] { color: var(--workspace-primary); background: #fff; box-shadow: 0 3px 10px rgb(40 44 70 / 9%); }
  .px-content-workspace__switcher span { display: grid; min-width: 18px; height: 18px; place-items: center; border-radius: 999px; padding: 0 5px; color: inherit; background: var(--workspace-soft); font-size: 9px; }
  .px-content-workspace__grid { display: block; }
  .px-content-workspace__panel { display: none; }
  .px-content-workspace__panel.is-active { display: block; }
}

@media (max-width: 460px) {
  .px-content-workspace__hero { grid-template-columns: minmax(0, 1fr); }
  .px-content-workspace__compose { grid-column: 1; grid-row: auto; width: 100%; }
  .px-content-workspace__stats { display: grid; grid-template-columns: repeat(3, 1fr); }
  .px-content-workspace__stats > div { min-width: 0; padding-inline: 5px; }
  .px-content-workspace__stats dt { white-space: normal; }
}

@media (prefers-reduced-motion: reduce) {
  .px-content-workspace__compose { transition: none; }
}
</style>
