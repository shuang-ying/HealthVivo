// Native glue: bind the watch link layer to the BlueOS modules it needs.
//
// Everything with a decision in it lives in a sibling module that the Node test suite can drive
// without a watch. This file only performs the imports, so a missing native module fails here in
// one obvious place instead of being smeared across the protocol.

import bluetooth from '@blueos.bluetooth.ble'
import cipher from '@blueos.security.cipher'
import { createLinkServerCore } from './server-core.mjs'

export { wire, ADVERTISE_SETTING, ADVERTISE_DATA, HEALTH_INTERVAL, READVERTISE_AFTER, REOPEN_AFTER } from './server-core.mjs'

export function createLinkServer(identity, archive, hooks) {
  return createLinkServerCore({ ble: bluetooth, cipher, identity, archive, hooks })
}
