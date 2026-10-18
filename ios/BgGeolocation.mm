#import "BgGeolocation.h"

#import <UIKit/UIKit.h>

#import "BgGeolocation-Swift.h"

@implementation BgGeolocation

+ (void)load
{
  [[NSNotificationCenter defaultCenter]
      addObserverForName:UIApplicationDidFinishLaunchingNotification
                  object:nil
                   queue:nil
              usingBlock:^(NSNotification *note) {
                [BgGeoRuntime.shared launchIfNeeded];
              }];
}

- (instancetype)init
{
  if (self = [super init]) {
    __weak BgGeolocation *weakSelf = self;
    BgGeoRuntime.shared.onEvent = ^(NSString *name, NSDictionary<NSString *, id> *payload) {
      NSMutableDictionary *event = [payload mutableCopy];
      event[@"name"] = name;
      [weakSelf emitOnEvent:event];
    };
  }
  return self;
}

- (void)ready:(NSDictionary *)config resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [BgGeoRuntime.shared configure:config completion:^{
    resolve(BgGeoRuntime.shared.snapshot);
  }];
}

- (void)start:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [BgGeoRuntime.shared startWithCompletion:^(NSError *error) {
    if (error) {
      reject(@"E_START", error.localizedDescription, error);
    } else {
      resolve(BgGeoRuntime.shared.snapshot);
    }
  }];
}

- (void)stop:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [BgGeoRuntime.shared stopWithCompletion:^{
    resolve(BgGeoRuntime.shared.snapshot);
  }];
}

- (void)changePace:(BOOL)isMoving resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [BgGeoRuntime.shared changePace:isMoving completion:^{
    resolve(BgGeoRuntime.shared.snapshot);
  }];
}

- (void)getState:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(BgGeoRuntime.shared.snapshot);
}

- (void)getCount:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(BgGeoRuntime.shared.snapshot[@"count"]);
}

- (void)getLocations:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(BgGeoRuntime.shared.locations);
}

- (void)destroyLocations:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [BgGeoRuntime.shared destroyLocations];
  resolve(@YES);
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeBgGeolocationSpecJSI>(params);
}

+ (NSString *)moduleName
{
  return @"BgGeolocation";
}

@end
