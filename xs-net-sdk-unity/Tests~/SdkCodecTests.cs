using Game.Message;
using Google.Protobuf;
using Xunit;

namespace SdkTests;

public class RpcRouteTests
{
    [Fact]
    public void RouteValuesMatchProtocol()
    {
        Assert.Equal(999, (int)RpcRoute.RpcLogRecordCommand);
        Assert.Equal(1000, (int)RpcRoute.RpcCsLogin);
        Assert.Equal(1001, (int)RpcRoute.RpcCsLogout);
        Assert.Equal(1002, (int)RpcRoute.RpcScLogin);
        Assert.Equal(1003, (int)RpcRoute.RpcScLogout);
        Assert.Equal(2100, (int)RpcRoute.RpcCsLogReport);
        Assert.Equal(2101, (int)RpcRoute.RpcScLogReport);
        Assert.Equal(3003, (int)RpcRoute.RpcPlayerEnterCommand);
    }
}

public class LoginCodecTests
{
    [Fact]
    public void CsLogin_RoundTrip_PreservesFields()
    {
        var request = new CS_Login
        {
            PlayerId = "player-1001",
            PlayerName = "Alice",
            SessionId = "session-abc"
        };

        byte[] payload = request.ToByteArray();
        var decoded = CS_Login.Parser.ParseFrom(payload);

        Assert.Equal(request.PlayerId, decoded.PlayerId);
        Assert.Equal(request.PlayerName, decoded.PlayerName);
        Assert.Equal(request.SessionId, decoded.SessionId);
    }

    [Fact]
    public void ScLogin_RoundTrip_PreservesFields()
    {
        var response = new SC_Login
        {
            Result = 0,
            PlayerId = "player-1001",
            PlayerName = "Alice",
            Token = "token-xyz"
        };

        byte[] payload = response.ToByteArray();
        var decoded = SC_Login.Parser.ParseFrom(payload);

        Assert.Equal(response.Result, decoded.Result);
        Assert.Equal(response.PlayerId, decoded.PlayerId);
        Assert.Equal(response.PlayerName, decoded.PlayerName);
        Assert.Equal(response.Token, decoded.Token);
    }

    [Fact]
    public void DispatchByRoute_ParsesCorrectType()
    {
        var response = new SC_Login { Result = 0, PlayerId = "p", PlayerName = "n", Token = "t" };
        byte[] payload = response.ToByteArray();

        SC_Login? decoded = null;
        switch ((RpcRoute)1002)
        {
            case RpcRoute.RpcScLogin:
                decoded = SC_Login.Parser.ParseFrom(payload);
                break;
        }

        Assert.NotNull(decoded);
        Assert.Equal("p", decoded!.PlayerId);
    }
}

public class LogoutCodecTests
{
    [Fact]
    public void CsLogout_RoundTrip_PreservesFields()
    {
        var request = new CS_Logout { PlayerId = "player-1001", SessionId = "session-abc" };
        byte[] payload = request.ToByteArray();
        var decoded = CS_Logout.Parser.ParseFrom(payload);
        Assert.Equal(request.PlayerId, decoded.PlayerId);
        Assert.Equal(request.SessionId, decoded.SessionId);
    }

    [Fact]
    public void ScLogout_RoundTrip_PreservesFields()
    {
        var response = new SC_Logout { PlayerId = "player-1001", SessionId = "session-abc" };
        byte[] payload = response.ToByteArray();
        var decoded = SC_Logout.Parser.ParseFrom(payload);
        Assert.Equal(response.PlayerId, decoded.PlayerId);
        Assert.Equal(response.SessionId, decoded.SessionId);
    }
}

public class LogReportCodecTests
{
    [Fact]
    public void CsLogReport_RoundTrip_PreservesFields()
    {
        var request = new CS_LogReport
        {
            SessionId = "session-abc",
            LogLevel = 2,
            LogType = 3,
            Content = "some log"
        };
        byte[] payload = request.ToByteArray();
        var decoded = CS_LogReport.Parser.ParseFrom(payload);
        Assert.Equal(request.SessionId, decoded.SessionId);
        Assert.Equal(request.LogLevel, decoded.LogLevel);
        Assert.Equal(request.LogType, decoded.LogType);
        Assert.Equal(request.Content, decoded.Content);
    }

    [Fact]
    public void ScLogReport_RoundTrip_PreservesFields()
    {
        var response = new SC_LogReport { SessionId = "session-abc", Result = 1, LogId = 999L };
        byte[] payload = response.ToByteArray();
        var decoded = SC_LogReport.Parser.ParseFrom(payload);
        Assert.Equal(response.SessionId, decoded.SessionId);
        Assert.Equal(response.Result, decoded.Result);
        Assert.Equal(response.LogId, decoded.LogId);
    }
}
