# xs-net

xs-net is a Java 21 game-server framework with a Gateway, Login node, and
clustered Logic node. The actor runtime is Apache Pekko, Netty handles client
connections, Redis stores runtime state, and PostgreSQL receives batched writes.

The project is currently in an engineering-hardening phase. Review the Quick
Start and architecture documentation before evaluating it for production use.
