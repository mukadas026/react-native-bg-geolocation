import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  ReferenceCore,
  type Config,
  type CoreEvent,
  type EngineState,
  type GeofenceResult,
  type Input,
  type TrackingState,
} from './model.ts';

type Expectation = {
  state?: TrackingState;
  engine?: EngineState;
  events?: CoreEvent[];
  requests?: string[][];
  buffer?: string[];
  result?: GeofenceResult;
  geofences?: string[];
};

type Fixture = {
  name: string;
  description: string;
  config?: Partial<Config>;
  server?: number[];
  steps: { at: number; input: Input; expect?: Expectation }[];
};

const fixturesDir = join(import.meta.dirname, '..', 'fixtures');

function loadFixtures(): { file: string; fixture: Fixture }[] {
  return readdirSync(fixturesDir, { recursive: true, encoding: 'utf8' })
    .filter((file) => file.endsWith('.json'))
    .sort()
    .map((file) => ({
      file,
      fixture: JSON.parse(readFileSync(join(fixturesDir, file), 'utf8')),
    }));
}

const fixtures = loadFixtures();

test('fixture names are unique and match their file name', () => {
  const names = fixtures.map(({ fixture }) => fixture.name);
  assert.equal(new Set(names).size, names.length);
  for (const { file, fixture } of fixtures) {
    assert.ok(
      file.endsWith(`${fixture.name}.json`),
      `${file} declares name "${fixture.name}"`
    );
  }
});

for (const { file, fixture } of fixtures) {
  test(file, () => {
    const core = new ReferenceCore(fixture.config ?? {}, fixture.server ?? []);
    let previousAt = -Infinity;

    fixture.steps.forEach(({ at, input, expect }, index) => {
      assert.ok(at >= previousAt, `step ${index}: time went backwards`);
      previousAt = at;

      const result = core.step(input, at);
      const where = `step ${index} (${input.type} @${at})`;

      if (expect?.state !== undefined) {
        assert.equal(result.state, expect.state, `${where}: state`);
      }
      if (expect?.engine !== undefined) {
        assert.equal(result.engine, expect.engine, `${where}: engine`);
      }
      if (expect?.events !== undefined) {
        assert.deepEqual(result.events, expect.events, `${where}: events`);
      }
      if (expect?.requests !== undefined) {
        assert.deepEqual(
          result.requests,
          expect.requests,
          `${where}: requests`
        );
      }
      if (expect?.buffer !== undefined) {
        assert.deepEqual(result.buffer, expect.buffer, `${where}: buffer`);
      }
      if (expect?.result !== undefined) {
        assert.equal(result.result, expect.result, `${where}: result`);
      }
      if (expect?.geofences !== undefined) {
        assert.deepEqual(
          result.geofences,
          expect.geofences,
          `${where}: geofences`
        );
      }
    });
  });
}
