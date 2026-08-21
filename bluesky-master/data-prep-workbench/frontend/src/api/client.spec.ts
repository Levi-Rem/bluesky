import { afterEach, describe, expect, it, vi } from 'vitest';
import { listAll } from './client';

function response(body: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => body
  } as Response;
}

describe('listAll', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('跨越后端 200 条分页上限读取完整数据', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        response({ items: Array.from({ length: 200 }, (_, id) => ({ id })), page: 0, size: 200, total: 205 })
      )
      .mockResolvedValueOnce(
        response({ items: Array.from({ length: 5 }, (_, id) => ({ id: id + 200 })), page: 1, size: 200, total: 205 })
      );
    vi.stubGlobal('fetch', fetchMock);

    const result = await listAll<{ id: number }>('nav-point');

    expect(result).toHaveLength(205);
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/nav-point?page=1&size=200',
      expect.any(Object)
    );
  });
});
