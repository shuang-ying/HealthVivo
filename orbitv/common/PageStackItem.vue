<template>
  <div class="route-page">
    <component :is="pageComponent" />
  </div>
</template>

<script>
export default {
  name: 'PageStackItem',

  props: {
    pageComponent: {
      type: [Object, Function],
      required: true,
    },
  },
}
</script>
