import { describe, expect, it } from 'vitest';
import { drawerConfigs } from './config';

describe('导航点编辑配置', () => {
  it('与后端支持的导航点类型完全一致', () => {
    const typeField = drawerConfigs['nav-point'].fields.find(field => field.key === 'pointType');
    expect(typeField?.options).toEqual(['FIX', 'VOR', 'NDB', 'DME', 'VOR_DME', 'ILS', 'OTHER']);
  });
});
