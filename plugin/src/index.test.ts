import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyInfoPlist } from './index.ts';

test('adds the location background mode to an empty plist', () => {
  const result = applyInfoPlist({});
  assert.deepEqual(result.UIBackgroundModes, ['location']);
});

test('keeps existing background modes and does not duplicate location', () => {
  const result = applyInfoPlist({ UIBackgroundModes: ['fetch', 'location'] });
  assert.deepEqual(result.UIBackgroundModes, ['fetch', 'location']);
});

test('appends location to other background modes', () => {
  const result = applyInfoPlist({ UIBackgroundModes: ['remote-notification'] });
  assert.deepEqual(result.UIBackgroundModes, [
    'remote-notification',
    'location',
  ]);
});

test('provides default usage descriptions', () => {
  const result = applyInfoPlist({});
  assert.match(
    String(result.NSLocationAlwaysAndWhenInUseUsageDescription),
    /background/
  );
  assert.ok(result.NSLocationWhenInUseUsageDescription);
});

test('keeps usage descriptions the app already set', () => {
  const result = applyInfoPlist({
    NSLocationAlwaysAndWhenInUseUsageDescription: 'existing always',
    NSLocationWhenInUseUsageDescription: 'existing when in use',
  });
  assert.equal(
    result.NSLocationAlwaysAndWhenInUseUsageDescription,
    'existing always'
  );
  assert.equal(
    result.NSLocationWhenInUseUsageDescription,
    'existing when in use'
  );
});

test('props override existing usage descriptions', () => {
  const result = applyInfoPlist(
    { NSLocationAlwaysAndWhenInUseUsageDescription: 'existing' },
    {
      iosAlwaysUsageDescription: 'from props',
      iosWhenInUseUsageDescription: 'when in use from props',
    }
  );
  assert.equal(
    result.NSLocationAlwaysAndWhenInUseUsageDescription,
    'from props'
  );
  assert.equal(
    result.NSLocationWhenInUseUsageDescription,
    'when in use from props'
  );
});

test('leaves unrelated keys untouched', () => {
  const result = applyInfoPlist({ CFBundleName: 'App', UIBackgroundModes: [] });
  assert.equal(result.CFBundleName, 'App');
});
