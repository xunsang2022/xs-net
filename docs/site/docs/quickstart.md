# Quick Start

1. Start local Redis and PostgreSQL from the repository root:

   ```powershell
   docker compose up -d
   ```

2. Install the shared framework reactor:

   ```powershell
   .\mvnw.cmd -f xs-net\pom.xml install
   ```

3. Run the game-server verification reactor:

   ```powershell
   .\mvnw.cmd -f game-server\pom.xml test
   ```

4. Start Login, Gateway, and Logic using the IDE run configurations or their
   module main classes. The default ports are Login `8081`, Gateway HTTP `8080`,
   Gateway TCP `8082`, Gateway UDP `2002`, and Logic metrics `9090`.

For a two-Logic-node cluster validation run, use
`deploy/cluster/README.md` and provide local secrets through environment
variables instead of committing them to configuration files.
