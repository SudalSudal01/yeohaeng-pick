"""지원용 PDF와 정적 웹의 콘텐츠를 같은 원본으로 생성합니다.

실사용 DB·환경변수·키를 읽지 않습니다. 공개를 허용한 프로필과 검증된 설명만 content.json에 둡니다.
한국어 글꼴을 PDF 안에 포함해 지원 회사의 PC에 같은 글꼴이 없어도 읽을 수 있게 합니다.
"""
from pathlib import Path
from xml.sax.saxutils import escape
import json
import re
import shutil
import zipfile
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "portfolio"
SITE = BASE / "site"
OUTPUT = ROOT / "output" / "pdf"
OUTPUT.mkdir(parents=True, exist_ok=True)
data = json.loads((BASE / "content.json").read_text(encoding="utf-8"))
profile, projects = data["profile"], data["projects"]
pdfmetrics.registerFont(TTFont("Korean", "C:/Windows/Fonts/malgun.ttf"))
pdfmetrics.registerFont(TTFont("KoreanBold", "C:/Windows/Fonts/malgunbd.ttf"))
INK, MUTED, ACCENT, LINE, PAPER = map(HexColor, ["#1c2d35", "#57666d", "#2459bd", "#d9ded9", "#f5f3ed"])
W, H = A4
M = 44
CW = W - M * 2
pdf_path = OUTPUT / "kim-myeonghyeon-portfolio.pdf"
c = canvas.Canvas(str(pdf_path), pagesize=A4)
c.setTitle("김명현 | 백엔드 개발자 포트폴리오")
c.setAuthor("김명현")
c.setSubject("제때약 · 여행픽 · 5ASIS의 담당 기능과 설계·검증")
page_number = 0


def text(value, x, y, size=10.5, color=INK, bold=False, width=None, leading=None):
    """상단 좌표로 문단을 배치하고 다음 문단의 좌표를 반환합니다. 고정 길이로 잘라내지 않습니다."""
    style = ParagraphStyle("body", fontName="KoreanBold" if bold else "Korean", fontSize=size,
                           leading=leading or size * 1.65, textColor=color, wordWrap="LTR")
    p = Paragraph(escape(str(value)).replace("\n", "<br/>"), style)
    _, height = p.wrap(width or CW, H)
    if y + height > H - 57:
        raise ValueError(f"페이지 {page_number}: 문구가 하단을 넘습니다: {str(value)[:50]}")
    p.drawOn(c, x, H - y - height)
    return y + height


def line(y):
    c.setStrokeColor(LINE)
    c.setLineWidth(.6)
    c.line(M, H-y, W-M, H-y)


def linked_text(value, url, x, y, size, color):
    """표시된 연락처/주소와 같은 영역에만 링크를 붙여 PDF의 클릭 대상이 어긋나지 않게 합니다."""
    bottom = text(value, x, y, size, color)
    width = pdfmetrics.stringWidth(value, "Korean", size)
    c.linkURL(url, (x, H-bottom, x+width, H-y), relative=0)
    return bottom


def page(label):
    global page_number
    if page_number:
        c.showPage()
    page_number += 1
    c.setFillColor(PAPER)
    c.rect(0, 0, W, H, fill=1, stroke=0)
    text("KIM MYEONGHYEON / BACKEND", M, 22, 8.2, MUTED)
    text(label, M, 47, 9, ACCENT, True)
    line(H - 44)
    c.setFont("Korean", 8)
    c.setFillColor(MUTED)
    c.drawString(M, 27, "김명현 · 백엔드 포트폴리오 / 2026.10")
    c.drawRightString(W-M, 27, f"{page_number:02d} / 08")


def title(value, y=76, size=29):
    return text(value, M, y, size, bold=True, leading=size*1.35) + 20


def section(label, body, y, size=10.5):
    """문장마다 줄을 바꾸고 같은 역할의 설명을 카드 안에 묶습니다. 높이는 실제 문단으로 계산합니다."""
    body = re.sub(r"(?<=[.!?])\s+(?=[가-힣A-Z])", "\n", str(body))
    style = ParagraphStyle("card", fontName="Korean", fontSize=size,
                           leading=size*1.5, textColor=INK, wordWrap="LTR")
    paragraph = Paragraph(escape(body).replace("\n", "<br/>"), style)
    _, body_height = paragraph.wrap(CW-28, H)
    card_height = 8 + 9.5*1.65 + 4 + body_height + 8
    if y + card_height > H - 57:
        raise ValueError(f"페이지 {page_number}: 카드가 하단을 넘습니다: {label}")
    c.setFillColor(HexColor("#fffefb"))
    c.setStrokeColor(LINE)
    c.roundRect(M, H-y-card_height, CW, card_height, 7, fill=1, stroke=1)
    body_y = text(label, M+14, y+8, 9.5, ACCENT, True, width=CW-28) + 4
    text(body, M+14, body_y, size, width=CW-28, leading=size*1.5)
    return y + card_height + 10


def flow(items, y):
    gap = 9
    box_w = (CW - gap * (len(items)-1)) / len(items)
    for index, item in enumerate(items):
        x = M + index * (box_w+gap)
        c.setStrokeColor(LINE)
        c.setFillColor(HexColor("#fffefb"))
        c.roundRect(x, H-y-66, box_w, 66, 6, fill=1, stroke=1)
        text(f"0{index+1}", x+11, y+9, 8, ACCENT, width=box_w-22)
        text(item, x+11, y+27, 9, bold=True, width=box_w-22, leading=14)
    return y+88


def project_intro(project, y=78):
    y = text(project["name"], M, y, 34, bold=True, leading=44) + 6
    y = text(project["subtitle"], M, y, 15, MUTED) + 12
    y = text(f'{project["type"]}  /  {project["period"]}', M, y, 9.5, ACCENT) + 12
    overview = re.sub(r"(?<=[.!?])\s+(?=[가-힣A-Z])", "\n", project["overview"])
    y = text(overview, M, y, leading=16) + 14
    y = section("본인 담당", project["role"], y)
    return text("기술: "+" · ".join(project["stack"]), M, y, 9.5, MUTED) + 16


def case(item, y, include_limit=True):
    y = text(item["title"], M, y, 16, bold=True) + 12
    y = section("문제", item["problem"], y)
    y = section("처리 방식", item["action"], y)
    y = section("코드 근거", item["evidence"], y, 9)
    if include_limit:
        y = section("범위와 한계", item["limit"], y, 9.5)
    return y


# 1. 지원자가 누구인지와 세 프로젝트의 우선순위를 먼저 보여 줍니다.
page("BACKEND DEVELOPER / SELECTED WORKS")
# 원본 사진의 비율을 그대로 사용합니다. 제목은 사진 왼쪽에 배치해 서로 겹치지 않게 합니다.
portrait = ImageReader(str(SITE / profile["photo"].removeprefix("./")))
photo_width = 108
image_width, image_height = portrait.getSize()
photo_height = photo_width * image_height / image_width
c.drawImage(portrait, W-M-photo_width, H-100-photo_height,
            width=photo_width, height=photo_height, mask="auto")
y = text(profile["greeting"]+"\n"+profile["aspiration"]+"\n"+profile["introductionName"], M, 100, 29,
         bold=True, width=CW-photo_width-24, leading=29*1.45) + 20
y = max(y, 100+photo_height+12)
intro = re.sub(r"(?<=[.!?])\s+(?=[가-힣A-Z])", "\n", profile["intro"])
y = text(intro, M, y+7, 12, MUTED, width=470) + 28
y = text("프로젝트 경험", M, y, 23, bold=True) + 5
y = text("팀 프로젝트 2개 · 개인 프로젝트 1개", M, y, 11, MUTED) + 37
line(y)
y += 25
for project in projects:
    y = section(f'{project["number"]}   {project["name"]}', project["role"], y, 10)
y = linked_text(profile["phone"], "tel:"+profile["phone"].replace("-", ""), M, y+8, 12, ACCENT) + 4
y = linked_text(profile["email"], "mailto:"+profile["email"], M, y, 10.5, ACCENT) + 4
linked_text(profile["github"], profile["github"], M, y, 9.5, MUTED)

# 2-4. 주력 팀 프로젝트에서 본인이 담당한 세 영역을 각각 설명합니다.
jette, travel, oasis = projects
page("01 / 제때약 / AI 상담 챗봇")
y = project_intro(jette)
y = flow(jette["flow"], y)
case(jette["cases"][0], y)

page("01 / 제때약 / 내 약 관리")
y = title("등록 기록과\n현재 복용 상태를 나누다.")
y = text("담당 기능: 내 약 관리", M, y, 11, ACCENT) + 24
y = flow(["세션 사용자", "소유 등록 확인", "기간·상태 판정", "DUR 비교"], y)
y = case(jette["cases"][1], y)
section("설계에서 고려한 점", "처방약·상비약·상시약의 등록 출처를 유지하면서 상태를 별도로 관리합니다. 동일 제품의 여러 등록과 현재 복용 여부를 같은 개념으로 취급하지 않았습니다.", y)

page("01 / 제때약 / 커뮤니티와 검증")
y = title("삭제 이후에도\n권한과 연결을 지키다.")
y = case(jette["cases"][2], y)
line(y)
y = section("검증 기록의 읽는 법", jette["verification"], y+22, 10)
section("배운 점", jette["learning"], y, 11)

# 5-6. 개인 프로젝트는 요구사항과 상태 일관성·Oracle 이전에 집중합니다.
page("02 / 여행픽 / 조건을 지키는 일정 변경")
y = project_intro(travel)
y = flow(travel["flow"], y)
case(travel["cases"][0], y)

page("02 / 여행픽 / 저장소와 접근 권한")
y = title("DB가 달라도,\n접근 권한이 바뀌어도.") - 10
y = case(travel["cases"][1], y)
line(y)
y += 20
y = text(travel["cases"][2]["title"], M, y, 16, bold=True) + 14
y = section("처리 방식", travel["cases"][2]["action"], y)
y = section("검증", travel["verification"], y, 9.5)
section("현재 상태", "실시간 좌석·객실·결제 확인은 미구현이며 외부 예매 결과는 사용자가 기록합니다.\nAI 개발 도구를 활용한 개인 프로젝트로, 공개 운영은 미완료입니다.", y, 9.5)

# 7. 이전 팀 프로젝트는 외부 API 어댑터와 화면 데이터 계약 경험을 담습니다.
page("03 / 5ASIS / 메인 대시보드와 Steam API")
y = title(oasis["name"], 78, 34)
y = text(oasis["subtitle"], M, y, 14, MUTED) + 18
y = section("팀 프로젝트 / 본인 담당", oasis["role"], y)
y = section("기술", " · ".join(oasis["stack"]), y, 9.5)
y = case(oasis["cases"][0], y)
section("확인 범위", oasis["verification"], y, 9.5)

# 8. 연락처와 근거 출처를 남기고 확인하지 않은 경력·학력·성과 수치를 채우지 않습니다.
page("DEVELOPMENT APPROACH / CONTACT")
y = title("조회에서 변경까지,\n설명할 수 있는 개발.")
for item in profile["strengths"]:
    y = section(item["title"], item["text"], y, 11)
line(y)
y += 22
y = text("코드와 확인 근거", M, y, 9.5, ACCENT, True) + 8
# 각 링크의 실제 문단 높이로 클릭 영역을 계산합니다. 개인 포크와 팀 원본을 함께 명시합니다.
for project in projects:
    label = f'{project["name"]}: {project["repo"].removeprefix("https://")}'
    if project.get("private"):
        y = text(label+" (비공개)", M, y, 9.5)
    else:
        y = linked_text(label, project["repo"], M, y, 9.5, MUTED)
    if project.get("upstream"):
        y = linked_text("팀 원본: "+project["upstream"].removeprefix("https://"),
                        project["upstream"], M, y, 9, MUTED)
y = text("팀 담당 기능: 본인 확인 및 팀 결과보고서 / 검증 단위·시점은 각 설명에 구분", M, y, 9) + 22
y = section("표기 원칙", "팀 전체 기능을 개인 기여로 표현하지 않았습니다. 과거 팀 검증 기록과 이번에 재실행한 검사를 구분했습니다. 경력·학력·성과 수치는 확인되지 않은 내용을 추가하지 않았습니다.", y, 9.5)
y = text("김명현  /  백엔드 개발자", M, y+4, 19, bold=True) + 13
y = linked_text(profile["phone"], "tel:"+profile["phone"].replace("-", ""), M, y, 15, ACCENT) + 9
y = linked_text(profile["email"], "mailto:"+profile["email"], M, y, 10.5, ACCENT) + 4
linked_text(profile["github"], profile["github"], M, y, 10, MUTED)

c.save()
shutil.copyfile(BASE / "content.json", SITE / "data.json")
(SITE / "assets").mkdir(exist_ok=True)
shutil.copyfile(pdf_path, SITE / "assets" / pdf_path.name)
web_output = ROOT / "output" / "web"
web_output.mkdir(parents=True, exist_ok=True)
# 배포 폴더만 압축합니다. 저장소 코드·환경설정·팀 원본 보고서·실사용 데이터는 포함하지 않습니다.
with zipfile.ZipFile(web_output / "kim-myeonghyeon-portfolio-web.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(SITE.rglob("*")):
        if path.is_file():
            archive.write(path, path.relative_to(SITE))
print(f"PDF: {pdf_path}")
print("8 pages / web content synchronized")
