# Mobile UX & Haptic Feedback Skill

## Guidelines
- Interactive buttons, swipe cards, and form submissions should trigger native haptics where appropriate.
- Import from @capacitor/haptics:
  import { Haptics, ImpactStyle } from '@capacitor/haptics';
  await Haptics.impact({ style: ImpactStyle.Light });
- Ensure bottom sheets and sliding modals handle Android hardware/gesture back-navigation.
