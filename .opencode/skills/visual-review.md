# Visual Verification Skill

When asked to build, restyle, or refactor a UI component:
1. Ensure the Vite dev server is running on port 5173.
2. Run the headless screenshot command:
   bun run scripts/snap-ui.ts http://localhost:5173/ <route-or-component> preview.png
3. Check mobile viewport bounds (Samsung Galaxy S22+ 412x915) for clipping or overflow.
