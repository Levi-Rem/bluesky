/** 后端 REST 客户端：全部走 /api 相对路径（dev 由 Vite 代理，生产同源静态资源）。 */

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface ApiErrorBody {
  status: number;
  message: string;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init
  });
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = (await response.json()) as ApiErrorBody;
      message = body.message || message;
    } catch {
      /* 非 JSON 错误体 */
    }
    throw new ApiError(response.status, message);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export function list<T>(entity: string, page = 0, size = 20): Promise<PageResult<T>> {
  return request<PageResult<T>>(`/api/${entity}?page=${page}&size=${size}`);
}

/** 遍历后端分页上限，返回实体的完整数据集。 */
export async function listAll<T>(entity: string, size = 200): Promise<T[]> {
  const items: T[] = [];
  let page = 0;
  while (true) {
    const result = await list<T>(entity, page, size);
    items.push(...result.items);
    if (items.length >= result.total || result.items.length < result.size) {
      return items;
    }
    page += 1;
  }
}

export function get<T>(entity: string, id: string): Promise<T> {
  return request<T>(`/api/${entity}/${id}`);
}

export function create<T>(entity: string, body: unknown): Promise<T> {
  return request<T>(`/api/${entity}`, { method: 'POST', body: JSON.stringify(body) });
}

export function update<T>(entity: string, id: string, body: unknown): Promise<T> {
  return request<T>(`/api/${entity}/${id}`, { method: 'PUT', body: JSON.stringify(body) });
}

export function remove(entity: string, id: string, revision: number): Promise<void> {
  return request<void>(`/api/${entity}/${id}?revision=${revision}`, { method: 'DELETE' });
}

export function changeStatus<T>(entity: string, id: string, status: string, revision: number): Promise<T> {
  return request<T>(`/api/${entity}/${id}/status?status=${status}&revision=${revision}`, {
    method: 'POST'
  });
}

export interface Health {
  status: string;
  revision: number;
}

export function health(): Promise<Health> {
  return request<Health>('/api/health');
}

export interface MapFeature {
  featureId: string;
  entityId: string;
  entityType: string;
  code: string;
  name: string;
  revision: number;
  geometry: Record<string, unknown> | null;
}

export interface MapLayerData {
  category: string;
  name: string;
  count: number;
  features: MapFeature[];
}

export function mapLayers(): Promise<{ layers: MapLayerData[] }> {
  return request<{ layers: MapLayerData[] }>('/api/map/layers');
}

export interface MapOperation {
  operationType: 'CREATE' | 'UPDATE_GEOMETRY' | 'UPDATE_PROPERTIES' | 'DELETE';
  entityType: string;
  entityId: string;
  featureId?: string;
  revision: number;
  geometry?: string;
  properties?: Record<string, unknown>;
}

export function saveMapFeatures(operations: MapOperation[]): Promise<{ saved: number }> {
  return request<{ saved: number }>('/api/map/features', {
    method: 'PUT',
    body: JSON.stringify({ operations })
  });
}

export function templateUrl(entity: string): string {
  return `/api/templates/${entity}`;
}

export function exportUrl(entity: string): string {
  return `/api/export/${entity}`;
}

export interface ImportResult {
  batchId: string;
  dataType: string;
  totalRows: number;
  successRows: number;
  failedRows: number;
  batchStatus: string;
}

export async function importExcel(entity: string, file: File): Promise<ImportResult> {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch(`/api/imports/${entity}`, { method: 'POST', body: form });
  const body = await response.json();
  if (!response.ok) {
    throw new ApiError(response.status, (body as ApiErrorBody).message || '导入失败');
  }
  return body as ImportResult;
}

export interface ImportErrorRow {
  id: string;
  rowNumber: number;
  fieldName: string;
  errorCode: string;
  errorMessage: string;
}

export function importErrors(batchId: string): Promise<ImportErrorRow[]> {
  return request<ImportErrorRow[]>(`/api/imports/${batchId}/errors`);
}

export interface AsfImportResult {
  navigationPointCount: number;
  airwayCount: number;
  airwaySegmentCount: number;
  codedRouteCount: number;
  sidCount: number;
  starCount: number;
  duplicateDefinitionCount: number;
  duplicateDefinitions: string[];
}

export async function replaceAirspaceFromAsf(
  characteristicPoints: File,
  routes: File
): Promise<AsfImportResult> {
  const form = new FormData();
  form.append('characteristicPoints', characteristicPoints);
  form.append('routes', routes);
  form.append('confirmReplace', 'true');
  const response = await fetch('/api/asf/replace-airspace', { method: 'POST', body: form });
  const body = await response.json();
  if (!response.ok) {
    throw new ApiError(response.status, (body as ApiErrorBody).message || 'ASF 导入失败');
  }
  return body as AsfImportResult;
}

export interface PhysicalSectorImportResult {
  sourceSectorCount: number;
  sourceFirCount: number;
  regionCount: number;
  boundaryPointCount: number;
}

export async function replacePhysicalSectorsFromAsf(file: File): Promise<PhysicalSectorImportResult> {
  const form = new FormData();
  form.append('fdpVolumes', file);
  form.append('confirmReplace', 'true');
  const response = await fetch('/api/asf/replace-physical-sectors', { method: 'POST', body: form });
  const body = await response.json();
  if (!response.ok) {
    throw new ApiError(response.status, (body as ApiErrorBody).message || '物理扇区 ASF 导入失败');
  }
  return body as PhysicalSectorImportResult;
}

export interface AircraftPerformanceImportResult {
  performanceGroupCount: number;
  aircraftTypeCount: number;
  performanceRowCount: number;
  warnings: string[];
}

export async function replaceAircraftPerformancesFromAsf(
  file: File
): Promise<AircraftPerformanceImportResult> {
  const form = new FormData();
  form.append('aircraftPerformances', file);
  form.append('confirmReplace', 'true');
  const response = await fetch('/api/asf/replace-aircraft-performances', { method: 'POST', body: form });
  const body = await response.json();
  if (!response.ok) {
    throw new ApiError(response.status, (body as ApiErrorBody).message || '飞机性能 ASF 导入失败');
  }
  return body as AircraftPerformanceImportResult;
}
