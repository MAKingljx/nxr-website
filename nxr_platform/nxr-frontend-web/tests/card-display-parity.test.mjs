import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'

import {
  aiProviderNotice,
  pythonAiCopy,
  pythonGradeText,
  pythonLanguageLabel,
  pythonVideoAction,
} from '../src/lib/cardDisplayParity.ts'

test('Python language labels and final-grade presentation remain the Java card standard', () => {
  assert.equal(pythonLanguageLabel('CT'), 'Chinese Traditional')
  assert.equal(pythonLanguageLabel('CS'), 'Chinese Simplified')
  assert.equal(pythonGradeText({ finalGradeLabel: '10', finalGradeValue: 9.8 }), '10')
  assert.equal(pythonGradeText({ finalGradeLabel: null, finalGradeValue: 9.5 }), '9.5')
})

test('Python AI labels and related-video actions are category-aware', () => {
  assert.deepEqual(pythonAiCopy('movie_film'), {
    button: 'AI Film Info', subtitle: 'Learn about this film', modal: 'AI Film Information',
  })
  const action = pythonVideoAction({
    brandName: 'Pokemon', cardCategory: 'trading_card', cardName: 'Quaxly', playerName: '',
    movieName: null, releaseYear: null, productionCompany: null, filmType: null, groupName: null,
    varietyName: '', finalGradeLabel: null, finalGradeValue: null,
  })
  assert.equal(action?.label, 'Watch Anime')
  assert.match(action?.href ?? '', /Pokemon%20Quaxly%20anime/)
})

test('local AI fallback is disclosed instead of being presented as generated context', () => {
  assert.match(aiProviderNotice('local', false), /Configure DeepSeek/)
  assert.equal(aiProviderNotice('deepseek', true), 'Cached DeepSeek AI context.')
})

test('public Java card view follows the Python display boundary', () => {
  const view = readFileSync(new URL('../src/views/WebCardView.vue', import.meta.url), 'utf8')

  assert.match(view, /pythonLanguageLabel\(detail\.languageCode\)/)
  assert.match(view, /Sub-Grades Breakdown/)
  assert.match(view, /v-if="videoAction"/)
  assert.match(view, /ai-provider-note/)
  assert.doesNotMatch(view, /Ownership History|Sign in to bind this card|transferCustomerCard/)
})
