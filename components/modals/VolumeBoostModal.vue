<template>
  <modals-modal v-model="show" :width="200" height="100%">
    <template #outer>
      <div class="absolute top-8 left-4 z-40">
        <p class="text-white text-2xl truncate">{{ $strings.LabelVolumeBoost }}</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-border" style="max-height: 75%" @click.stop>
        <ul class="w-full" role="listbox" :aria-label="$strings.LabelVolumeBoost">
          <template v-for="boost in boosts">
            <li :key="boost" class="text-fg select-none relative py-4" :class="boost === volumeBoost ? 'bg-bg-hover/50' : ''" role="option" :aria-selected="boost === volumeBoost" @click="$emit('change', boost)">
              <div class="flex items-center justify-center">
                <span class="font-normal block truncate text-lg">{{ getLabel(boost) }}</span>
              </div>
            </li>
          </template>
        </ul>
        <div class="flex items-center justify-center py-3 border-t border-fg/10">
          <button :disabled="volumeBoost <= 0" class="icon-num-btn w-8 h-8 text-fg-muted rounded border border-border flex items-center justify-center" @click="set(volumeBoost - 1)">
            <span class="material-symbols">remove</span>
          </button>
          <div class="w-24 text-center">
            <p class="text-xl">{{ getLabel(volumeBoost) }}</p>
          </div>
          <button :disabled="volumeBoost >= maxBoost" class="icon-num-btn w-8 h-8 text-fg-muted rounded border border-border flex items-center justify-center" @click="set(volumeBoost + 1)">
            <span class="material-symbols">add</span>
          </button>
        </div>
        <p class="text-xs text-fg-muted text-center py-3 px-2 border-t border-fg/10">{{ $strings.MessageVolumeBoostSavedForBook }}</p>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    volumeBoost: Number
  },
  data() {
    return {
      maxBoost: 18
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    boosts() {
      return [0, 3, 6, 9, 12, 15, 18]
    }
  },
  methods: {
    getLabel(boost) {
      return boost ? `+${boost} dB` : this.$strings.LabelOff
    },
    set(boost) {
      this.$emit('change', Math.min(Math.max(boost, 0), this.maxBoost))
    }
  }
}
</script>
