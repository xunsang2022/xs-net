import argparse
import json
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class Player:
    player_id: int
    player_name: str
    session_id: str


def post_json(url: str, body: dict, headers: dict[str, str] | None = None) -> tuple[int, dict]:
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    request = Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=request_headers,
        method="POST",
    )
    try:
        with urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        response_body = error.read().decode("utf-8")
        raise RuntimeError(f"{url} returned HTTP {error.code}: {response_body}") from error
    except URLError as error:
        raise RuntimeError(f"Unable to reach {url}: {error.reason}") from error


def login_player(player: Player, login_base_url: str, client_base_url: str) -> Player:
    issue_status, issue_body = post_json(
        f"{login_base_url}/api/auth/issue",
        {"playerId": player.player_id, "playerName": player.player_name},
        {"X-Channel-Id": player.session_id},
    )
    if issue_status != 200 or not issue_body.get("token"):
        raise RuntimeError(f"Failed to issue token for player {player.player_id}: {issue_body}")

    connect_status, connect_body = post_json(
        f"{client_base_url}/sessions/{player.session_id}/connect",
        {"token": issue_body["token"]},
    )
    if connect_status != 201:
        raise RuntimeError(f"Failed to connect player {player.player_id}: {connect_body}")

    login_status, login_body = post_json(
        f"{client_base_url}/sessions/{player.session_id}/messages",
        {
            "rpcNum": 1000,
            "payload": {
                "playerId": str(player.player_id),
                "playerName": player.player_name,
                "sessionId": player.session_id,
            },
        },
    )
    if login_status != 202:
        raise RuntimeError(f"Failed to send login for player {player.player_id}: {login_body}")
    return player


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="通过登录和客户端 HTTP 接口模拟玩家登录")
    parser.add_argument("--login-base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--client-base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--start-player-id", type=int, default=10001)
    parser.add_argument("--count", type=int, default=1)
    parser.add_argument("--player-name-prefix", default="player-")
    parser.add_argument("--workers", type=int, default=1)
    args = parser.parse_args()
    if args.count < 1:
        parser.error("--count must be at least 1")
    if args.workers < 1:
        parser.error("--workers must be at least 1")
    return args


def main() -> int:
    args = parse_args()
    players = [
        Player(
            player_id=args.start_player_id + offset,
            player_name=f"{args.player_name_prefix}{args.start_player_id + offset}",
            session_id=f"player-{args.start_player_id + offset}-{uuid.uuid4().hex}",
        )
        for offset in range(args.count)
    ]
    failures = []
    with ThreadPoolExecutor(max_workers=min(args.workers, args.count)) as executor:
        futures = {
            executor.submit(login_player, player, args.login_base_url.rstrip("/"), args.client_base_url.rstrip("/")): player
            for player in players
        }
        for future in as_completed(futures):
            player = futures[future]
            try:
                completed_player = future.result()
                print(f"登录请求已发送: playerId={completed_player.player_id}, sessionId={completed_player.session_id}")
            except RuntimeError as error:
                failures.append((player.player_id, str(error)))
                print(f"登录失败: playerId={player.player_id}, reason={error}", file=sys.stderr)

    print(f"完成: success={len(players) - len(failures)}, failed={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
