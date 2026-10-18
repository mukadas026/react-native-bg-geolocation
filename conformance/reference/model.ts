export type TrackingState = 'stopped' | 'moving' | 'stationary';
export type EngineState = 'off' | 'continuous' | 'armed';

export type Config = {
  distanceFilter: number;
  stopTimeout: number;
  stationaryRadius: number;
  autoSync: boolean;
  maxBatchSize: number;
  syncRetryBaseDelay: number;
  syncRetryMaxDelay: number;
  maxRecordsToPersist: number;
  geofenceLimit: number;
};

export const DEFAULT_CONFIG: Config = {
  distanceFilter: 10,
  stopTimeout: 5,
  stationaryRadius: 25,
  autoSync: true,
  maxBatchSize: 100,
  syncRetryBaseDelay: 10,
  syncRetryMaxDelay: 300,
  maxRecordsToPersist: -1,
  geofenceLimit: 20,
};

export type Input =
  | { type: 'start' }
  | { type: 'stop' }
  | { type: 'changePace'; isMoving: boolean }
  | { type: 'fix'; id: string; dx: number; dy: number }
  | { type: 'motionSignal' }
  | { type: 'tick' }
  | { type: 'connectivity'; online: boolean }
  | { type: 'restart' }
  | {
      type: 'addGeofence';
      id: string;
      dx: number;
      dy: number;
      radius: number;
      notifyOnEntry?: boolean;
      notifyOnExit?: boolean;
    }
  | { type: 'removeGeofence'; id: string }
  | { type: 'geofenceCheck'; dx: number; dy: number }
  | { type: 'geofenceTransition'; id: string; action: 'ENTER' | 'EXIT' };

export type GeofenceResult = 'ok' | 'limit' | 'invalid' | 'removed' | 'unknown';

export type CoreEvent =
  | { type: 'enabledchange'; enabled: boolean }
  | { type: 'motionchange'; isMoving: boolean }
  | { type: 'location'; id: string }
  | { type: 'buffer-overflow'; dropped: string[] }
  | { type: 'geofence'; id: string; action: 'ENTER' | 'EXIT' };

export type StepResult = {
  state: TrackingState;
  engine: EngineState;
  events: CoreEvent[];
  requests: string[][];
  buffer: string[];
  result: GeofenceResult | undefined;
  geofences: string[];
};

type Geofence = {
  id: string;
  center: Position;
  radius: number;
  notifyOnEntry: boolean;
  notifyOnExit: boolean;
  inside: boolean | null;
};

type Position = { lat: number; lon: number };

const EARTH_RADIUS = 6371008.8;
const METERS_PER_DEGREE = 111194.93;

export function toPosition(dx: number, dy: number): Position {
  return { lat: dy / METERS_PER_DEGREE, lon: dx / METERS_PER_DEGREE };
}

export function distance(a: Position, b: Position): number {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLon = (b.lon - a.lon) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS * Math.asin(Math.sqrt(h));
}

export class ReferenceCore {
  private readonly config: Config;
  private readonly server: number[];
  private serverIndex = 0;

  private state: TrackingState = 'stopped';
  private anchor: Position | null = null;
  private anchorSince = 0;
  private lastRecorded: Position | null = null;
  private forceNextRecord = false;

  private buffer: string[] = [];
  private online = true;
  private failures = 0;
  private retryAt: number | null = null;

  private geofences: Geofence[] = [];

  private events: CoreEvent[] = [];
  private requests: string[][] = [];
  private result: GeofenceResult | undefined;

  constructor(config: Partial<Config>, server: number[]) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.server = server;
  }

  step(input: Input, at: number): StepResult {
    this.events = [];
    this.requests = [];
    this.result = undefined;

    switch (input.type) {
      case 'start':
        this.start();
        break;
      case 'stop':
        this.stop();
        break;
      case 'changePace':
        if (input.isMoving) {
          this.resume();
        } else if (this.state === 'moving') {
          this.settle();
        }
        break;
      case 'fix':
        this.onFix(input.id, toPosition(input.dx, input.dy), at);
        break;
      case 'motionSignal':
        this.resume();
        break;
      case 'tick':
        break;
      case 'connectivity':
        this.online = input.online;
        if (this.online) {
          this.failures = 0;
          this.retryAt = null;
          this.autoFlush(at);
        }
        break;
      case 'addGeofence':
        this.addGeofence(input);
        break;
      case 'removeGeofence':
        this.result = this.removeGeofence(input.id);
        break;
      case 'geofenceCheck':
        if (this.state !== 'stopped') {
          this.evaluateGeofences(toPosition(input.dx, input.dy));
        }
        break;
      case 'geofenceTransition':
        this.onGeofenceTransition(input.id, input.action);
        break;
      case 'restart':
        this.anchor = null;
        this.lastRecorded = null;
        this.forceNextRecord = false;
        this.failures = 0;
        this.retryAt = null;
        this.autoFlush(at);
        break;
    }

    this.evaluateStop(at);
    if (this.retryAt !== null && at >= this.retryAt) {
      this.flush(at);
    }

    return {
      state: this.state,
      engine: this.engine(),
      events: this.events,
      requests: this.requests,
      buffer: [...this.buffer],
      result: this.result,
      geofences: this.geofences.map((geofence) => geofence.id),
    };
  }

  private engine(): EngineState {
    if (this.state === 'moving') return 'continuous';
    if (this.state === 'stationary') return 'armed';
    return 'off';
  }

  private start(): void {
    if (this.state !== 'stopped') return;
    this.state = 'moving';
    this.anchor = null;
    this.lastRecorded = null;
    this.forceNextRecord = false;
    this.events.push({ type: 'enabledchange', enabled: true });
  }

  private stop(): void {
    if (this.state === 'stopped') return;
    this.state = 'stopped';
    this.anchor = null;
    this.events.push({ type: 'enabledchange', enabled: false });
  }

  private settle(): void {
    this.state = 'stationary';
    this.anchor = null;
    this.events.push({ type: 'motionchange', isMoving: false });
  }

  private resume(): void {
    if (this.state !== 'stationary') return;
    this.state = 'moving';
    this.anchor = null;
    this.forceNextRecord = true;
    this.events.push({ type: 'motionchange', isMoving: true });
  }

  private onFix(id: string, position: Position, at: number): void {
    if (this.state !== 'moving') return;

    if (
      this.anchor === null ||
      distance(position, this.anchor) > this.config.stationaryRadius
    ) {
      this.anchor = position;
      this.anchorSince = at;
    }

    const shouldRecord =
      this.lastRecorded === null ||
      this.forceNextRecord ||
      distance(position, this.lastRecorded) >= this.config.distanceFilter;

    if (shouldRecord) {
      this.lastRecorded = position;
      this.forceNextRecord = false;
      this.buffer.push(id);
      this.events.push({ type: 'location', id });
      this.enforceRetention();

      const retryPending = this.retryAt !== null && at < this.retryAt;
      if (!retryPending) {
        this.autoFlush(at);
      }
    }

    this.evaluateGeofences(position);
  }

  private addGeofence(input: Extract<Input, { type: 'addGeofence' }>): void {
    if (!Number.isFinite(input.radius) || input.radius <= 0) {
      this.result = 'invalid';
      return;
    }
    const geofence: Geofence = {
      id: input.id,
      center: toPosition(input.dx, input.dy),
      radius: input.radius,
      notifyOnEntry: input.notifyOnEntry ?? true,
      notifyOnExit: input.notifyOnExit ?? true,
      inside: null,
    };
    const index = this.geofences.findIndex((g) => g.id === input.id);
    if (index >= 0) {
      this.geofences[index] = geofence;
      this.result = 'ok';
    } else if (this.geofences.length >= this.config.geofenceLimit) {
      this.result = 'limit';
    } else {
      this.geofences.push(geofence);
      this.result = 'ok';
    }
  }

  private removeGeofence(id: string): GeofenceResult {
    const index = this.geofences.findIndex((g) => g.id === id);
    if (index < 0) return 'unknown';
    this.geofences.splice(index, 1);
    return 'removed';
  }

  private evaluateGeofences(position: Position): void {
    for (const geofence of this.geofences) {
      this.applyGeofenceReading(
        geofence,
        distance(position, geofence.center) <= geofence.radius
      );
    }
  }

  private onGeofenceTransition(id: string, action: 'ENTER' | 'EXIT'): void {
    if (this.state === 'stopped') return;
    const geofence = this.geofences.find((g) => g.id === id);
    if (geofence) {
      this.applyGeofenceReading(geofence, action === 'ENTER');
    }
  }

  private applyGeofenceReading(geofence: Geofence, inside: boolean): void {
    const previous = geofence.inside;
    geofence.inside = inside;
    if (inside && previous !== true && geofence.notifyOnEntry) {
      this.events.push({ type: 'geofence', id: geofence.id, action: 'ENTER' });
    } else if (!inside && previous === true && geofence.notifyOnExit) {
      this.events.push({ type: 'geofence', id: geofence.id, action: 'EXIT' });
    }
  }

  private enforceRetention(): void {
    const max = this.config.maxRecordsToPersist;
    if (max < 0 || this.buffer.length <= max) return;
    const dropped = this.buffer.splice(0, this.buffer.length - max);
    this.events.push({ type: 'buffer-overflow', dropped });
  }

  private evaluateStop(at: number): void {
    if (this.state !== 'moving' || this.anchor === null) return;
    if (at - this.anchorSince >= this.config.stopTimeout * 60) {
      this.settle();
    }
  }

  private autoFlush(at: number): void {
    if (this.config.autoSync) {
      this.flush(at);
    }
  }

  private flush(at: number): void {
    if (!this.online) return;
    while (this.buffer.length > 0) {
      const batch = this.buffer.slice(0, this.config.maxBatchSize);
      this.requests.push(batch);
      const status = this.server[this.serverIndex++] ?? 200;
      if (status >= 200 && status < 300) {
        this.buffer.splice(0, batch.length);
        this.failures = 0;
        this.retryAt = null;
        continue;
      }
      this.failures += 1;
      const delay = Math.min(
        this.config.syncRetryBaseDelay * 2 ** (this.failures - 1),
        this.config.syncRetryMaxDelay
      );
      this.retryAt = at + delay;
      return;
    }
  }
}
