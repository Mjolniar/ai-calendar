import { defineConfig } from "vitest/config";

// Tests that need the Firestore emulator. Run with `npm run test:emulator`, which starts the
// emulator for a demo project (it can never reach a real one) and sets FIRESTORE_EMULATOR_HOST.
export default defineConfig({
  test: {
    include: ["test/firestore-store.test.ts", "test/integration/**/*.test.ts"],
    // Transactions under contention wait on emulator locks; one file at a time keeps them apart.
    testTimeout: 60_000,
    hookTimeout: 60_000,
    fileParallelism: false,
  },
});
