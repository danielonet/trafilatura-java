Corpus: trafilatura's evaluation set, 990 pages. Single thread, after warm-up. Python 3.12.3.

| Tool | Language | Version | Precision | Recall | F1 | Pages/s | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- |
| trafilatura | python | 2.3.1 | 0.907 | 0.946 | **0.926** | 19.3 | 0 |
| trafilatura-java | java | 0.1.1-SNAPSHOT | 0.908 | 0.939 | **0.923** | 29.8 | 0 |
| trafilatura precision | python | 2.3.1 | 0.924 | 0.921 | **0.922** | 18.1 | 0 |
| trafilatura-java fast | java | 0.1.1-SNAPSHOT | 0.909 | 0.934 | **0.921** | 32.5 | 0 |
| trafilatura fast | python | 2.3.1 | 0.907 | 0.934 | **0.920** | 26.1 | 0 |
| trafilatura recall | python | 2.3.1 | 0.898 | 0.943 | **0.920** | 28.9 | 0 |
| trafilatura-java precision | java | 0.1.1-SNAPSHOT | 0.925 | 0.915 | **0.920** | 28.3 | 0 |
| trafilatura-java recall | java | 0.1.1-SNAPSHOT | 0.899 | 0.940 | **0.919** | 38.1 | 0 |
| readability4j | java | 1.0.8 | 0.868 | 0.905 | **0.886** | 57.3 | 1 |
| boilerpipe | java | 1.2.3 | 0.846 | 0.856 | **0.851** | 102.5 | 0 |
| jsoup text | java | 1.18.3 | 0.532 | 0.991 | **0.692** | 311.6 | 0 |
| everything (whole page) | - | - | 0.528 | 0.906 | **0.667** |  |  |
| nothing | - | - | 0.000 | 0.000 | **0.000** |  |  |
