# ServerDashboard

Paper 플러그인 — 웹 대시보드로 킥/밴, 주기적 공지를 관리합니다.

## 기능

- **플레이어 관리**: 온라인 플레이어 목록, 핑/월드 표시, 강퇴·밴
- **밴 관리**: 밴 목록 조회, 기간 지정 밴, 밴 해제
- **공지 관리**: 주기적 공지 추가/편집/삭제, 활성화 토글, `&a` 색상 코드 지원, 권한 필터
- **서버 상태**: TPS, 온라인 플레이어 수, 버전 정보 실시간 표시
- **보안**: API 토큰 인증

## 설치

1. [Releases](../../releases)에서 `ServerDashboard-1.0.0.jar` 다운로드
2. `plugins/` 폴더에 복사
3. 서버 재시작
4. `plugins/ServerDashboard/config.yml` 에서 토큰 변경

```yaml
web:
  port: 8080
  token: "여기를-변경하세요"
```

5. `http://서버IP:8080` 으로 접속 → 토큰 입력

## 명령어

| 명령어 | 설명 |
|--------|------|
| `/dashboard` | 포트 및 토큰 확인 |
| `/dashboard reload` | 설정 재로드 |
| `/dashboard token` | 새 랜덤 토큰 생성 |

## 빌드

```bash
./gradlew build
# build/libs/ServerDashboard-1.0.0.jar
```

## 호환성

- Paper 1.21.x 이상 (Minecraft Java Edition)
- Java 21+
