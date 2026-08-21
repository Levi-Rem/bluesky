import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import App from './App.vue';

describe('App 骨架', () => {
  it('渲染品牌、状态区与七个页面入口', () => {
    const wrapper = mount(App, {
      global: {
        plugins: [createPinia()],
        stubs: { DataTable: true }
      }
    });
    const text = wrapper.text();
    expect(text).toContain('飞行数据准备与分析');
    expect(text).toContain('修订');
    expect(text).toContain('空域数据');
    expect(text).toContain('气象数据');
    expect(text).toContain('机型性能');
    expect(text).toContain('雷达与通道');
    expect(text).toContain('数据编辑');
  });

  it('空域数据菜单包含机场入口', async () => {
    const wrapper = mount(App, {
      global: {
        plugins: [createPinia()],
        stubs: { DataTable: true }
      }
    });
    expect(wrapper.text()).toContain('导航数据');
    const nav = wrapper.findAll('.nav-button');
    expect(nav.length).toBeGreaterThanOrEqual(5);
  });
});
