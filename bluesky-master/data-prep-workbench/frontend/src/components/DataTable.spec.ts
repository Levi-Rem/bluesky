import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { list } from '../api/client';
import DataTable from './DataTable.vue';
import type { PageConfig } from '../pages/config';

vi.mock('../api/client', () => ({
  list: vi.fn(),
  ApiError: class ApiError extends Error {}
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

describe('DataTable', () => {
  it('页面切换后忽略上一个实体的迟到响应', async () => {
    const navigation = deferred<{ items: Record<string, unknown>[]; total: number }>();
    const weather = deferred<{ items: Record<string, unknown>[]; total: number }>();
    vi.mocked(list).mockImplementation((entity: string) => {
      const source = entity === 'weather' ? weather : navigation;
      return source.promise.then(result => ({ ...result, page: 0, size: 20 }));
    });

    const navigationConfig: PageConfig = {
      entity: 'nav-point',
      columns: ['名称', '操作'],
      cells: row => [String(row.name)]
    };
    const weatherConfig: PageConfig = {
      entity: 'weather',
      columns: ['名称', '操作'],
      cells: row => [String(row.name)]
    };
    const wrapper = mount(DataTable, { props: { config: navigationConfig } });
    await wrapper.setProps({ config: weatherConfig });

    weather.resolve({ items: [{ id: 'weather-1', name: '雷暴区' }], total: 1 });
    await flushPromises();
    expect(wrapper.text()).toContain('雷暴区');

    navigation.resolve({ items: [{ id: 'nav-1', name: 'AA' }], total: 1 });
    await flushPromises();
    expect(wrapper.text()).toContain('雷暴区');
    expect(wrapper.text()).not.toContain('AA');
  });
});
