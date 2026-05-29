#import "WhisperEngine.h"
#import <os/log.h>

#import "whisper.h"

#pragma mark - AisSegment

@implementation AisSegment
- (instancetype)initWithStartMs:(int64_t)startMs
                          endMs:(int64_t)endMs
                           text:(NSString *)text {
    self = [super init];
    if (!self) return nil;
    _startMs = startMs;
    _endMs = endMs;
    _text = [text copy];
    return self;
}
@end

#pragma mark - WhisperEngine

@implementation WhisperEngine {
    struct whisper_context *_ctx;
}

+ (NSString *)systemInfo {
    const char *info = whisper_print_system_info();
    return info ? [NSString stringWithUTF8String:info] : @"";
}

- (nullable instancetype)initWithModelPath:(NSString *)path useGpu:(BOOL)useGpu {
    self = [super init];
    if (!self) return nil;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu ? true : false;

    _ctx = whisper_init_from_file_with_params(path.UTF8String, cparams);
    if (!_ctx) {
        os_log_error(OS_LOG_DEFAULT, "whisper_init_from_file_with_params failed for %{public}@", path);
        return nil;
    }
    return self;
}

- (NSArray<AisSegment *> *)transcribePCM:(NSData *)pcm
                                language:(nullable NSString *)language
                                nThreads:(int32_t)nThreads
                                  onError:(NSError * _Nullable * _Nullable)error {
    if (!_ctx) {
        if (error) *error = [NSError errorWithDomain:@"AisWhisper" code:1
                                            userInfo:@{NSLocalizedDescriptionKey: @"context not initialized"}];
        return @[];
    }

    const float *samples = (const float *)pcm.bytes;
    int n_samples = (int)(pcm.length / sizeof(float));
    if (n_samples <= 0) {
        if (error) *error = [NSError errorWithDomain:@"AisWhisper" code:2
                                            userInfo:@{NSLocalizedDescriptionKey: @"empty PCM"}];
        return @[];
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_special    = false;
    params.print_timestamps = true;
    params.translate        = false;
    params.detect_language  = (language == nil);
    params.language         = language ? language.UTF8String : "auto";
    params.n_threads        = nThreads > 0 ? nThreads : 4;
    params.no_context       = true;
    params.single_segment   = false;
    params.suppress_blank   = true;

    int rc = whisper_full(_ctx, params, samples, n_samples);
    if (rc != 0) {
        if (error) *error = [NSError errorWithDomain:@"AisWhisper" code:rc
                                            userInfo:@{NSLocalizedDescriptionKey:
                                                          [NSString stringWithFormat:@"whisper_full rc=%d", rc]}];
        return @[];
    }

    int n = whisper_full_n_segments(_ctx);
    NSMutableArray<AisSegment *> *out = [NSMutableArray arrayWithCapacity:n];
    for (int i = 0; i < n; i++) {
        int64_t t0 = whisper_full_get_segment_t0(_ctx, i) * 10; // 10ms units
        int64_t t1 = whisper_full_get_segment_t1(_ctx, i) * 10;
        const char *txt = whisper_full_get_segment_text(_ctx, i);
        NSString *s = txt ? [NSString stringWithUTF8String:txt] : @"";
        [out addObject:[[AisSegment alloc] initWithStartMs:t0 endMs:t1 text:s]];
    }
    return out;
}

- (void)dealloc {
    if (_ctx) {
        whisper_free(_ctx);
        _ctx = NULL;
    }
}

@end
