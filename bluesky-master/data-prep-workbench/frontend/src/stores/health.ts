import { defineStore } from 'pinia';
import { health, type Health } from '../api/client';

/** 服务健康与全局修订号：5 秒轮询，页面右上角展示。 */
export const useHealthStore = defineStore('health', {
  state: (): { online: boolean; revision: number } => ({
    online: false,
    revision: 0
  }),
  actions: {
    async refresh() {
      try {
        const data: Health = await health();
        this.online = data.status === 'UP';
        this.revision = data.revision;
      } catch {
        this.online = false;
      }
    },
    bumpRevision() {
      this.revision += 1;
      void this.refresh();
    }
  }
});
