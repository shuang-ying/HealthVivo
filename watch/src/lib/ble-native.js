import bluetooth from '@blueos.bluetooth.ble'
import cipher from '@blueos.security.cipher'
import identity from '../common/ble.local'
import { createBleServer } from './ble-transport.mjs'
export default { open(hooks) { return createBleServer(bluetooth, cipher, identity, hooks) } }
