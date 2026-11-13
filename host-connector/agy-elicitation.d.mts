export type AgyForm = Record<string, { required: boolean; min: number; max: number; values?: string[] }>
export function parseAgyElicitation(params: unknown): AgyForm
export function validateAgyElicitationResult(fields: AgyForm, result: unknown): boolean
export function cancelAgyRequest(pending: { backend: string; method: string; upstreamId: string | number; client: { respond(id: string | number, value: unknown): Promise<void>; respondError(id: string | number, code: number, message: string): Promise<void> } }, reason: string): Promise<void>
