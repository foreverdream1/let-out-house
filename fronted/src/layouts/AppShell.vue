<template>
  <ClickSpark :spark-color="palette.primary" :spark-size="8" :spark-radius="18" :duration="380">
  <div class="flex h-dvh w-full overflow-hidden bg-surface text-on-surface">
    <!-- 桌面 / 平板：左侧 Navigation Rail（≥ 840px） -->
    <nav
      class="hidden md:flex w-24 shrink-0 flex-col items-center gap-2 px-2 pt-[max(1.25rem,env(safe-area-inset-top))] pb-5"
      aria-label="主导航"
    >
      <Magnet :padding="36" :magnet-strength="3" wrapper-class-name="mb-4">
        <button
          type="button"
          class="flex h-12 w-12 items-center justify-center rounded-2xl bg-primary-container transition-transform hover:scale-105 active:scale-95 cursor-pointer"
          title="Let Out House"
          @click="go('home')"
        >
          <span class="logo-mask h-7 w-7 bg-on-primary-container" :style="{ '--logo': `url(&quot;${logo}&quot;)` }" />
        </button>
      </Magnet>

      <div class="flex w-full flex-col gap-3">
        <NavItem
          v-for="r in rails"
          :key="r.value"
          :icon="r.icon"
          :label="r.label"
          :active="activeRail === r.value"
          @select="go(r.value)"
        />
      </div>

      <div class="flex-1" />

      <NavItem icon="account_circle" label="我的" :active="activeRail === 'profile'" class="w-full" @select="go('profile')" />
    </nav>

    <!-- 主内容区：桌面为圆角面板，手机全屏 -->
    <div class="flex min-w-0 flex-1 flex-col md:py-3 md:pr-3">
      <main
        class="relative flex min-h-0 flex-1 flex-col overflow-hidden md:rounded-[28px] md:bg-surface-container-low md:[--mdui-color-background:var(--mdui-color-surface-container-low)] md:border md:border-outline-variant/30"
      >
        <!-- 顶栏（页面自带标题栏时隐藏，如对话页） -->
        <header
          v-if="!hideHeader"
          class="z-10 flex shrink-0 items-center gap-1 px-2 sm:px-4 lg:px-6 pt-[env(safe-area-inset-top)] md:pt-0 h-[calc(4rem+env(safe-area-inset-top))] md:h-16"
        >
          <Transition name="fade-scale">
            <mdui-button-icon v-if="showBack" icon="arrow_back" @click="back"></mdui-button-icon>
          </Transition>
          <Transition name="title" mode="out-in">
            <h1 :key="title" class="truncate px-2 text-xl sm:text-[22px] font-medium text-on-surface">
              {{ title }}
            </h1>
          </Transition>
          <div class="flex-1" />
          <mdui-button-icon
            :icon="isDark ? 'light_mode--outlined' : 'dark_mode--outlined'"
            :title="isDark ? '切换到浅色' : '切换到深色'"
            @click="setMode(isDark ? 'light' : 'dark')"
          ></mdui-button-icon>
          <mdui-avatar
            class="ml-1 h-9 w-9 cursor-pointer bg-tertiary-container text-on-tertiary-container text-sm md:hidden"
            @click="go('profile')"
          >
            {{ initial }}
          </mdui-avatar>
        </header>

        <!-- 滚动区 -->
        <div
          ref="scroller"
          class="flex-1 min-h-0"
          :class="fullHeight ? 'overflow-hidden' : 'overflow-y-auto overflow-x-hidden'"
        >
          <div
            class="mx-auto w-full max-w-7xl px-4 sm:px-6 lg:px-10"
            :class="fullHeight ? 'h-full pt-3 pb-[calc(5.5rem+env(safe-area-inset-bottom))] md:pb-6' : 'pt-3 pb-[calc(6.5rem+env(safe-area-inset-bottom))] md:pb-12'"
          >
            <RouterView v-slot="{ Component, route: r }">
              <Transition name="page" mode="out-in" @after-leave="resetScroll">
                <component :is="Component" :key="r.path" />
              </Transition>
            </RouterView>
          </div>
        </div>
      </main>
    </div>

    <!-- 手机：底部 Navigation Bar（< 840px） -->
    <nav
      class="md:hidden fixed inset-x-0 bottom-0 z-20 flex h-[calc(5rem+env(safe-area-inset-bottom))] items-stretch bg-surface-container pb-[env(safe-area-inset-bottom)] shadow-[0_-1px_0_rgb(var(--mdui-color-outline-variant)/0.4)]"
      aria-label="主导航"
    >
      <NavItem
        v-for="r in mobileItems"
        :key="r.value"
        compact
        :icon="r.icon"
        :label="r.label"
        :active="activeRail === r.value"
        @select="go(r.value)"
      />
    </nav>
  </div>
  </ClickSpark>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import NavItem from "../components/NavItem.vue";
import ClickSpark from "../components/ClickSpark.vue";
import Magnet from "../components/Magnet.vue";
import logo from "../assets/logo.svg";
import { useTheme } from "../composables/useTheme";

const route = useRoute();
const router = useRouter();
const { isDark, palette, setMode } = useTheme();
const scroller = ref<HTMLElement | null>(null);

const rails = [
  { value: "home", label: "主页", icon: "home" },
  { value: "category", label: "分类", icon: "grid_view" },
];
const mobileItems = [...rails, { value: "profile", label: "我的", icon: "account_circle" }];

const PATH_MAP: Record<string, string> = {
  home: "/",
  category: "/category",
  profile: "/profile",
};

// 当前高亮：路由 meta.rail 优先，其次按路径推断
const activeRail = computed(() => {
  if (route.meta.rail) return route.meta.rail as string;
  if (route.path.startsWith("/category")) return "category";
  if (route.path.startsWith("/profile")) return "profile";
  return "home";
});

const hideHeader = computed(() => !!route.meta.hideHeader);
const title = computed(() => (route.meta.title as string) || "Let Out House");
const showBack = computed(() => route.path.startsWith("/profile/"));
const fullHeight = computed(() => !!route.meta.fullHeight);

const user = JSON.parse(localStorage.getItem("user") || "null");
const initial = computed(() => (user?.nickname || user?.username || "?").slice(0, 1).toUpperCase());

const go = (value: string) => {
  const to = PATH_MAP[value];
  if (to && route.path !== to) router.push(to);
};

const back = () => {
  if (window.history.state?.back) router.back();
  else router.push("/profile");
};

const resetScroll = () => scroller.value?.scrollTo({ top: 0 });
</script>

<style scoped>
.logo-mask {
  -webkit-mask: var(--logo) center / contain no-repeat;
  mask: var(--logo) center / contain no-repeat;
}

.title-enter-active,
.title-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.22s cubic-bezier(0.2, 0, 0, 1);
}
.title-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.title-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.fade-scale-enter-active,
.fade-scale-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.fade-scale-enter-from,
.fade-scale-leave-to {
  opacity: 0;
  transform: scale(0.6);
}
</style>
