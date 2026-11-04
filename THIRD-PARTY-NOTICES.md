# Bundled runtime libraries

StateLink's LGPL-3.0-only declaration does not replace the terms of these separately nested JARs:

| Library | Version | License/notice location |
|---|---|---|
| HikariCP | 5.0.1 | Apache License 2.0; `META-INF/licenses/HikariCP-5.0.1.txt` |
| MySQL Connector/J | 8.0.33 | Original `LICENSE` within `META-INF/jars/mysql-connector-j-8.0.33.jar`, including its vendor and third-party terms |
| SLF4J API | 1.7.36 | MIT; `META-INF/licenses/slf4j-api-1.7.36.txt` |

Upstream sources and notices:

- https://github.com/brettwooldridge/HikariCP/tree/HikariCP-5.0.1
- https://dev.mysql.com/doc/connector-j/8.0/en/
- https://github.com/qos-ch/slf4j/tree/v_1.7.36

The nested JARs remain replaceable runtime components. No dependency is relicensed by StateLink.
