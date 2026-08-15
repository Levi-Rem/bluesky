<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from './api'
import { validateAircraftForm } from './aircraftForm'
import { validateAircraftReferences } from './aircraftReferenceValidation'

defineProps<{ disabled?: boolean }>()

const emit = defineEmits<{ created: [] }>()
const open = ref(false)
const error = ref('')
const form = reactive({
  callsign: 'CCA3582', aircraftType: 'A320', wakeCategory: 'M', transponderCode: '1234',
  origin: 'ZSSS', destination: 'ZBAA', appearanceOffset: '0000', latitude: 31.1434,
  longitude: 121.8052, headingDegrees: 360, altitudeFeet: 9000, speedKnots: 250,
  route: 'ZSSS ZBAA'
})

async function submit() {
  error.value = ''
  error.value = validateAircraftForm(form.callsign, form.appearanceOffset, form.transponderCode)
  if (error.value) return
  try {
    error.value = await validateAircraftReferences(
      form.aircraftType, form.origin, form.destination, form.route, api.reference
    )
    if (error.value) return
    await api.createAircraft({
      ...form,
      appearanceOffsetMinutes: form.appearanceOffset,
      route: form.route.trim() ? form.route.trim().toUpperCase().split(/\s+/) : [form.destination.trim().toUpperCase()]
    })
    open.value = false
    emit('created')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : String(reason)
  }
}
</script>

<template>
  <button class="icon-button" title="创建航空器" :disabled="disabled" @click="open = true">＋</button>
  <div v-if="open" class="modal-backdrop" @click.self="open = false">
    <form class="aircraft-form panel" @submit.prevent="submit">
      <div class="form-grid">
        <label>呼号<input v-model="form.callsign" minlength="2" maxlength="7" pattern="[A-Za-z0-9]{2,7}" /></label>
        <label>机型<input v-model="form.aircraftType" maxlength="4" /></label>
        <label>尾流<input v-model="form.wakeCategory" maxlength="1" /></label>
        <label>二次代码<input v-model="form.transponderCode" maxlength="4" inputmode="numeric" pattern="[0-7]{4}" /></label>
        <label>起飞<input v-model="form.origin" maxlength="4" /></label>
        <label>落地<input v-model="form.destination" maxlength="4" /></label>
        <label>出现时间<input v-model="form.appearanceOffset" inputmode="numeric" minlength="4" maxlength="4" pattern="[0-9]{4}" /></label>
        <label>航向<input v-model.number="form.headingDegrees" type="number" /></label>
        <label>纬度<input v-model.number="form.latitude" type="number" step="0.0001" /></label>
        <label>经度<input v-model.number="form.longitude" type="number" step="0.0001" /></label>
        <label>高度<input v-model.number="form.altitudeFeet" type="number" /></label>
        <label>速度<input v-model.number="form.speedKnots" type="number" /></label>
        <label class="wide">航路<input v-model="form.route" /></label>
      </div>
      <div v-if="error" class="form-error">{{ error }}</div>
      <div class="form-actions"><button type="button" @click="open = false">取消</button><button type="submit">创建</button></div>
    </form>
  </div>
</template>
