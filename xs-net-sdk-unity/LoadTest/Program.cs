using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Game.Message;
using Game.Message.Networking;

var options = LoadTestOptions.Parse(args);
var qpsLabel = options.Qps > 0 ? options.Qps.ToString() : "unlimited";
Console.WriteLine($"protocol={options.Protocol} host={options.Host} port={options.Port} count={options.Count} concurrency={options.Concurrency} qps={qpsLabel}");

var semaphore = new SemaphoreSlim(options.Concurrency, options.Concurrency);
var results = new LoginResult?[options.Count];
var failed = 0;
var totalWatch = Stopwatch.StartNew();
var schedule = options.Qps > 0 ? CreateSchedule(options.Count, options.Qps) : null;
var tasks = Enumerable.Range(0, options.Count).Select(async _ =>
{
    var index = _;
    if (schedule is not null)
    {
        await schedule[index].Task;
    }
    await semaphore.WaitAsync();
    try
    {
        var result = await LoginAsync(options, index);
        results[index] = result;
        if (!result.Success)
        {
            Interlocked.Increment(ref failed);
            Console.Error.WriteLine($"player={index + options.PlayerStart} failed: {result.Error}");
        }
    }
    finally
    {
        semaphore.Release();
    }
});
await Task.WhenAll(tasks);
totalWatch.Stop();

var succeeded = results.Where(result => result is { Success: true }).Select(result => result!).ToArray();
Console.WriteLine($"success={succeeded.Length} failed={failed} total_ms={totalWatch.Elapsed.TotalMilliseconds:F0} throughput={succeeded.Length / totalWatch.Elapsed.TotalSeconds:F2}/s");
if (succeeded.Length > 0)
{
    PrintLatency("connect", succeeded.Select(result => result.ConnectLatencyMs).ToArray());
    PrintLatency("login", succeeded.Select(result => result.LoginLatencyMs).ToArray());
    PrintLatency("end_to_end", succeeded.Select(result => result.TotalLatencyMs).ToArray());
}
Environment.ExitCode = failed == 0 ? 0 : 1;

static TaskCompletionSource<bool>[] CreateSchedule(int count, int qps)
{
    var signals = Enumerable.Range(0, count)
        .Select(_ => new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously))
        .ToArray();
    _ = ReleaseScheduleAsync(signals, qps);
    return signals;
}

static async Task ReleaseScheduleAsync(TaskCompletionSource<bool>[] signals, int qps)
{
    var startTimestamp = Stopwatch.GetTimestamp();
    for (var index = 0; index < signals.Length; index++)
    {
        await WaitUntilAsync(startTimestamp, index, qps);
        signals[index].TrySetResult(true);
    }
}

static async Task WaitUntilAsync(long startTimestamp, int index, int qps)
{
    var targetTimestamp = startTimestamp + (long)(index * (double)Stopwatch.Frequency / qps);
    while (true)
    {
        var remainingTicks = targetTimestamp - Stopwatch.GetTimestamp();
        if (remainingTicks <= 0)
        {
            return;
        }

        var remainingMs = remainingTicks * 1000.0 / Stopwatch.Frequency;
        if (remainingMs >= 1)
        {
            await Task.Delay(TimeSpan.FromMilliseconds(Math.Min(remainingMs, 1000)));
        }
        else
        {
            await Task.Yield();
        }
    }
}

static async Task<LoginResult> LoginAsync(LoadTestOptions options, int index)
{
    var playerId = (options.PlayerStart + index).ToString();
    var channelId = $"load-test-{playerId}-{Guid.NewGuid():N}";
    var jwt = JwtToken.Create(playerId, channelId, options.Secret, options.TokenLifetimeSeconds);
    var config = new NetworkConfig
    {
        Host = options.Host,
        TcpPort = options.Port,
        UdpPort = options.Port,
        UseUdp = options.Protocol == "udp",
        ConnectTimeoutMs = options.TimeoutMs,
        ReadTimeoutMs = options.TimeoutMs
    };
    using var client = new GameNetworkClient(config);
    var connected = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
    var response = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
    client.OnConnected += () => connected.TrySetResult(true);
    client.OnDisconnected += () =>
    {
        connected.TrySetResult(false);
        response.TrySetResult(false);
    };
    client.OnError += error =>
    {
        connected.TrySetResult(false);
        response.TrySetException(new IOException(error));
    };
    client.Dispatcher.Register<SC_Login>(RpcRoute.RpcScLogin, message => response.TrySetResult(message.Result == 0));

    var totalWatch = Stopwatch.StartNew();
    try
    {
        await Task.Run(() => client.Connect(jwt));
        if (!await WaitAsync(connected.Task, options.TimeoutMs))
        {
            return LoginResult.Failure("connect timeout");
        }

        var connectLatencyMs = totalWatch.Elapsed.TotalMilliseconds;
        var loginWatch = Stopwatch.StartNew();
        client.Send(RpcRoute.RpcCsLogin, new CS_Login
        {
            PlayerId = playerId,
            PlayerName = $"load-{playerId}",
            SessionId = channelId
        });
        if (!await WaitAsync(response.Task, options.TimeoutMs))
        {
            return LoginResult.Failure("login response timeout");
        }

        return response.Task.Result
            ? LoginResult.Succeeded(connectLatencyMs, loginWatch.Elapsed.TotalMilliseconds, totalWatch.Elapsed.TotalMilliseconds)
            : LoginResult.Failure("server returned unsuccessful login result");
    }
    catch (Exception exception)
    {
        return LoginResult.Failure(exception.Message);
    }
    finally
    {
        client.Disconnect();
    }
}

static async Task<bool> WaitAsync(Task<bool> task, int timeoutMs)
{
    var completed = await Task.WhenAny(task, Task.Delay(timeoutMs));
    return completed == task && await task;
}

static void PrintLatency(string name, double[] values)
{
    Array.Sort(values);
    Console.WriteLine($"{name}_avg_ms={values.Average():F2} {name}_p50_ms={Percentile(values, 0.50):F2} {name}_p95_ms={Percentile(values, 0.95):F2} {name}_p99_ms={Percentile(values, 0.99):F2} {name}_max_ms={values[^1]:F2}");
}

static double Percentile(double[] values, double percentile)
{
    var position = (values.Length - 1) * percentile;
    var lower = (int)Math.Floor(position);
    var upper = (int)Math.Ceiling(position);
    if (lower == upper)
    {
        return values[lower];
    }
    return values[lower] + (values[upper] - values[lower]) * (position - lower);
}

internal sealed record LoginResult(
    bool Success,
    double ConnectLatencyMs,
    double LoginLatencyMs,
    double TotalLatencyMs,
    string? Error)
{
    public static LoginResult Succeeded(double connectLatencyMs, double loginLatencyMs, double totalLatencyMs)
        => new(true, connectLatencyMs, loginLatencyMs, totalLatencyMs, null);

    public static LoginResult Failure(string error)
        => new(false, 0, 0, 0, error);
}

internal sealed class LoadTestOptions
{
    public string Host { get; private set; } = "127.0.0.1";
    public string Protocol { get; private set; } = "tcp";
    public int Port { get; private set; } = 8082;
    public int Count { get; private set; } = 1000;
    public int Concurrency { get; private set; } = 200;
    public int PlayerStart { get; private set; } = 10001;
    public string Secret { get; private set; } = "xs-net-dev-jwt-secret-key-32bytes-minimum!!";
    public int TimeoutMs { get; private set; } = 10000;
    public int TokenLifetimeSeconds { get; private set; } = 3600;
    public int Qps { get; private set; }

    public static LoadTestOptions Parse(string[] args)
    {
        var options = new LoadTestOptions();
        for (var i = 0; i < args.Length; i++)
        {
            var option = args[i];
            var value = i + 1 < args.Length ? args[++i] : throw new ArgumentException($"missing value for {option}");
            switch (option)
            {
                case "--host": options.Host = value; break;
                case "--protocol": options.Protocol = value.ToLowerInvariant(); break;
                case "--port": options.Port = int.Parse(value); break;
                case "--count": options.Count = int.Parse(value); break;
                case "--concurrency": options.Concurrency = int.Parse(value); break;
                case "--player-start": options.PlayerStart = int.Parse(value); break;
                case "--secret": options.Secret = value; break;
                case "--timeout-ms": options.TimeoutMs = int.Parse(value); break;
                case "--token-lifetime-seconds": options.TokenLifetimeSeconds = int.Parse(value); break;
                case "--qps": options.Qps = int.Parse(value); break;
                default: throw new ArgumentException($"unknown option {option}");
            }
        }
        if (options.Protocol is not ("tcp" or "udp")) throw new ArgumentException("--protocol must be tcp or udp");
        if (options.Count < 1 || options.Concurrency < 1 || options.Concurrency > options.Count) throw new ArgumentException("count and concurrency must be positive, concurrency cannot exceed count");
        if (options.Qps < 0) throw new ArgumentException("--qps must be zero or positive");
        return options;
    }
}

internal static class JwtToken
{
    public static string Create(string playerId, string channelId, string secret, int lifetimeSeconds)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var header = Base64Url(JsonSerializer.SerializeToUtf8Bytes(new { alg = "HS256", typ = "JWT" }));
        var payload = Base64Url(JsonSerializer.SerializeToUtf8Bytes(new { sub = playerId, cid = channelId, iat = now, exp = now + lifetimeSeconds }));
        var unsigned = $"{header}.{payload}";
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        return $"{unsigned}.{Base64Url(hmac.ComputeHash(Encoding.UTF8.GetBytes(unsigned)))}";
    }

    private static string Base64Url(byte[] bytes)
        => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
