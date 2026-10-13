const { test } = require('node:test')
const assert = require('node:assert/strict')
const protocol = require('../src/lib/protocol.mjs').default
test('invalid heart rate and acquisition timestamps are absent', () => {
  assert.equal(protocol.sample({value:0,timeStamp:10},100),null)
  assert.equal(protocol.sample({value:NaN,timeStamp:10},100),null)
  assert.equal(protocol.sample({value:80,timeStamp:NaN},100),null)
  assert.equal(protocol.sample({value:80,timeStamp:70000},100),null)
  assert.deepEqual(protocol.sample({value:80,timeStamp:10},100),{value:80,timestamp:10})
})
test('step baseline and reboot cannot import prior activity', () => {
  assert.equal(protocol.stepDelta(null,60000),0)
  assert.equal(protocol.stepDelta(60000,8),0)
  assert.equal(protocol.stepDelta(60000,60012),12)
})
test('only a matching durable acknowledgement clears a pending packet', () => {
  const queue=[protocol.envelope('fitness.ping.v1',{},100,'a'),protocol.envelope('fitness.ping.v1',{},101,'b')]
  assert.equal(protocol.acknowledge(queue,{type:'transport.success'}),queue)
  assert.equal(protocol.acknowledge(queue,{type:'fitness.ack.v1',data:{id:'a',stored:false}}),queue)
  assert.equal(protocol.acknowledge(queue,{type:'fitness.ack.v1',data:{id:'unknown',stored:true}}).length,2)
  assert.deepEqual(protocol.acknowledge(queue,JSON.stringify({type:'fitness.ack.v1',data:{id:'a',stored:true}})),[queue[1]])
})
