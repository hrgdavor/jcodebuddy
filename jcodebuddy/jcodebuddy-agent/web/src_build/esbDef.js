// Build definition for the agent dashboard — the same shape the compiler console uses.
export const esbDef = {
  tsconfig: 'tsconfig-custom.json',
  jsx: 'automatic',
  jsxImportSource: '@jsx6',
  format: 'esm',
  loader: { '.js': 'tsx', '.jsx': 'tsx' },
  bundle: true,
}
