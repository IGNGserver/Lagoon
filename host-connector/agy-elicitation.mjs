/** The Remote ACP gateway forwards only forms its clients can validate and display. */
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const keysWithin = (value, allowed) => Object.keys(value).every(key => allowed.includes(key))
const forbidden = new Set(['__proto__', 'prototype', 'constructor'])

export function parseAgyElicitation(params) {
  if (!record(params) || params.mode !== 'form' || !record(params.requestedSchema)) throw new Error('Unsupported elicitation')
  const schema = params.requestedSchema
  if (schema.type !== 'object' || !record(schema.properties) ||
      !keysWithin(schema, ['type', 'properties', 'required', 'additionalProperties', '$schema']) ||
      (schema.additionalProperties !== undefined && schema.additionalProperties !== false)) throw new Error('Unsupported form schema')
  const names = Object.keys(schema.properties)
  if (names.length < 1 || names.length > 32 || names.some(name => !name || name.length > 256 || forbidden.has(name))) throw new Error('Invalid form fields')
  const required = schema.required === undefined ? [] : schema.required
  if (!Array.isArray(required) || required.some(name => typeof name !== 'string' || !names.includes(name)) || new Set(required).size !== required.length) throw new Error('Invalid required fields')
  const fields = Object.create(null)
  for (const name of names) {
    const field = schema.properties[name]
    if (!record(field) || field.type !== 'string' || !keysWithin(field, ['type', 'title', 'description', 'enum', 'minLength', 'maxLength'])) throw new Error('Unsupported field')
    for (const label of ['title', 'description']) if (field[label] !== undefined && typeof field[label] !== 'string') throw new Error('Invalid field label')
    const min = field.minLength ?? 0
    const max = field.maxLength ?? 32768
    if (!Number.isSafeInteger(min) || !Number.isSafeInteger(max) || min < 0 || max > 32768 || min > max) throw new Error('Invalid field bounds')
    const values = field.enum
    if (values !== undefined && (!Array.isArray(values) || values.length < 1 || values.length > 64 ||
      values.some(value => typeof value !== 'string' || value.length === 0 || Array.from(value).length > 32768) || new Set(values).size !== values.length)) throw new Error('Invalid choices')
    fields[name] = { required: required.includes(name), min, max, values }
  }
  return fields
}

export function validateAgyElicitationResult(fields, result) {
  if (!record(fields) || !record(result) || result.action !== 'accept' || !record(result.content) ||
      !keysWithin(result, ['action', 'content'])) return false
  const names = Object.keys(fields)
  if (Object.keys(result.content).some(key => !names.includes(key))) return false
  return names.every(name => {
    const field = fields[name]
    const value = result.content[name]
    if (value === undefined) return !field.required
    if (typeof value !== 'string') return false
    const length = Array.from(value).length
    return length >= field.min && length <= field.max && (field.values === undefined || field.values.includes(value))
  })
}

/** ACP specifies explicit cancelled outcomes for its two user interaction requests. */
export async function cancelAgyRequest(pending, reason) {
  if (pending.backend === 'antigravity' && pending.method === 'session/request_permission') {
    return pending.client.respond(pending.upstreamId, { outcome: { outcome: 'cancelled' } })
  }
  if (pending.backend === 'antigravity' && pending.method === 'elicitation/create') {
    return pending.client.respond(pending.upstreamId, { action: 'cancel' })
  }
  return pending.client.respondError(pending.upstreamId, -32800, reason)
}
