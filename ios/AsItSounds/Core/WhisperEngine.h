#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// One ASR segment as emitted by `whisper_full`. Times are wall-clock ms.
@interface AisSegment : NSObject
@property (nonatomic, readonly) int64_t  startMs;
@property (nonatomic, readonly) int64_t  endMs;
@property (nonatomic, readonly, copy) NSString *text;
- (instancetype)initWithStartMs:(int64_t)startMs
                          endMs:(int64_t)endMs
                           text:(NSString *)text NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

/// Obj-C++ wrapper around whisper.cpp. Phase 1B adds `transcribePCM`.
@interface WhisperEngine : NSObject

+ (NSString *)systemInfo;

- (nullable instancetype)initWithModelPath:(NSString *)path
                                    useGpu:(BOOL)useGpu;

/// Runs `whisper_full` synchronously on `pcm` (16 kHz mono Float32). Returns
/// an empty array on failure. `language` is an ISO-639 code (e.g. `en`, `ja`)
/// or nil for auto-detect.
- (NSArray<AisSegment *> *)transcribePCM:(NSData *)pcm
                                language:(nullable NSString *)language
                                nThreads:(int32_t)nThreads
                                  onError:(NSError * _Nullable * _Nullable)error;

@end

NS_ASSUME_NONNULL_END
