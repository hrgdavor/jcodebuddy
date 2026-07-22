import { parseArgs } from 'util'

const {
  values: { 
    f=false,
    force=f,
    rec:recordid=26598575,
    from:dateStr = '2025-01-01',
    to:dateToStr = '2025-02-01',
    help,
  },
  // parseArgs returns array with bunPath, scriptPath and then actual script arguments
  positionals: [bunPath, scriptPath, ...args],
} = parseArgs({ args: Bun.argv, strict: false })


