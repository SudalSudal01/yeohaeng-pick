"""공개 콘텐츠로 지원용 PDF와 정적 웹 배포 묶음을 만듭니다.

소개 - 제때약 - 여행픽 - 5ASIS 순서로 읽히게 구성하며 실사용 DB나 환경변수를 읽지 않습니다.
각 문단의 실제 높이를 계산해 하단을 넘으면 중단하고, 한국어 글꼴을 PDF에 포함합니다.
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
BASE, OUTPUT = ROOT / "portfolio", ROOT / "output" / "pdf"
SITE = BASE / "site"
OUTPUT.mkdir(parents=True, exist_ok=True)
data = json.loads((BASE / "content.json").read_text(encoding="utf-8"))
profile, projects = data["profile"], data["projects"]
jette, travel, oasis = projects
pdfmetrics.registerFont(TTFont("Korean", "C:/Windows/Fonts/malgun.ttf"))
pdfmetrics.registerFont(TTFont("KoreanBold", "C:/Windows/Fonts/malgunbd.ttf"))
W, H = A4
M, CW = 42, A4[0] - 84
INK, MUTED, BLUE, LINE, LIGHT = map(HexColor, ["#172a45", "#58677a", "#315cdd", "#dce3eb", "#f3f6fa"])
COLORS = {"jette-yak": HexColor("#8e4055"), "travel-pick": HexColor("#187668"), "5asis": HexColor("#876b3e")}
pdf_path = OUTPUT / "kim-myeonghyeon-portfolio.pdf"
c = canvas.Canvas(str(pdf_path), pagesize=A4)
c.setTitle("김명현 | 백엔드 개발자 포트폴리오")
c.setAuthor("김명현")
c.setSubject("제때약 · 여행픽 · 5ASIS에서 구현한 기능과 개발 경험")
TOTAL_PAGES, page_number = 10, 0


def paragraph(value, width=CW, size=10.5, bold=False, color=INK, leading=None):
    """날짜·버전은 보존하며 문장마다 줄을 나누고 실제 높이를 구합니다."""
    value = re.sub(r"(?<=[.!?])\s+(?=[가-힣A-Z])", "\n", str(value))
    style = ParagraphStyle("portfolio", fontName="KoreanBold" if bold else "Korean", fontSize=size,
                           leading=leading or size * 1.6, textColor=color, wordWrap="LTR")
    p = Paragraph(escape(value).replace("\n", "<br/>"), style)
    _, height = p.wrap(width, H)
    return p, height


def text(value, x, top, width=CW, size=10.5, bold=False, color=INK, leading=None):
    p, height = paragraph(value, width, size, bold, color, leading)
    if top < 0 or top + height > H - 51:
        raise ValueError(f"페이지 {page_number}: 문단 범위를 벗어났습니다: {str(value)[:65]}")
    p.drawOn(c, x, H - top - height)
    return top + height


def rect(x, top, width, height, fill=LIGHT, radius=10, stroke=None):
    c.setFillColor(fill)
    c.setStrokeColor(stroke or fill)
    c.roundRect(x, H - top - height, width, height, radius, fill=1, stroke=bool(stroke))


def rule(top, color=LINE):
    c.setStrokeColor(color)
    c.setLineWidth(.6)
    c.line(M, H - top, W - M, H - top)


def page(label, color=BLUE):
    global page_number
    if page_number:
        c.showPage()
    page_number += 1
    c.setFillColor(HexColor("#ffffff"))
    c.rect(0, 0, W, H, fill=1, stroke=0)
    c.setFillColor(color)
    c.rect(M, H - 35, 24, 3, fill=1, stroke=0)
    text(label, M + 34, 25, CW - 34, 8.4, True, color)
    rule(H - 40)
    c.setFont("Korean", 8)
    c.setFillColor(MUTED)
    c.drawString(M, 23, "김명현  /  BACKEND PORTFOLIO  /  2026.11")
    c.drawRightString(W - M, 23, f"{page_number:02d} / {TOTAL_PAGES:02d}")


def title(value, top=66, size=29, width=CW, color=INK):
    return text(value, M, top, width, size, True, color, size * 1.35) + 22


def linked(value, url, x, top, size=10, color=BLUE, width=CW):
    bottom = text(value, x, top, width, size, False, color)
    link_width = min(width, pdfmetrics.stringWidth(value, "Korean", size))
    c.linkURL(url, (x, H - bottom, x + link_width, H - top), relative=0)
    return bottom


def block(label, body, top, color=BLUE, size=10.5, background=False):
    """제목·본문은 묶되 카드 배경은 직접 구현한 방식 등 강조할 곳에만 사용합니다."""
    inset = 16 if background else 0
    width = CW - inset * 2
    _, bh = paragraph(body, width, size)
    _, lh = paragraph(label, width, 10, True)
    height = inset * 2 + lh + 8 + bh
    if top + height > H - 51:
        raise ValueError(f"페이지 {page_number}: 설명 묶음이 넘쳤습니다: {label}")
    if background:
        rect(M, top, CW, height)
    y = text(label, M + inset, top + inset, width, 10, True, color) + 8
    text(body, M + inset, y, width, size)
    return top + height + 22


def flow(items, top, color):
    gap = 10
    bw = (CW - gap * (len(items) - 1)) / len(items)
    for i, item in enumerate(items):
        x = M + i * (bw + gap)
        rect(x, top, bw, 66, radius=8)
        text(f"0{i+1}", x + 12, top + 10, bw - 24, 8, True, color)
        text(item, x + 12, top + 29, bw - 24, 9, True, leading=13)
    return top + 88


def practice_steps(steps, top):
    """제가 정한 기준과 확인한 결과를 같은 높이의 카드에 담아 순서대로 읽도록 합니다."""
    gap, inset = 10, 12
    width = (CW - gap * (len(steps) - 1)) / len(steps)
    inner = width - 2 * inset
    lines = [
        (paragraph(step["label"], inner, 9.2, True)[1],
         paragraph(step["text"], inner, 9.2)[1])
        for step in steps
    ]
    height = 2 * inset + 7 + max(sum(pair) for pair in lines)
    if top + height > H - 51:
        raise ValueError(f"페이지 {page_number}: AI 활용 과정이 페이지 아래로 넘쳤습니다.")
    for i, step in enumerate(steps):
        x = M + i * (width + gap)
        rect(x, top, width, height, fill=LIGHT, radius=8, stroke=LINE)
        y = text(step["label"], x + inset, top + inset, inner, 9.2, True, BLUE) + 7
        text(step["text"], x + inset, y, inner, 9.2)
    return top + height + 12


def screenshot(media, top, height=292):
    """스크린샷은 비율을 유지해 크게 넣고 가상·로컬 촬영 설명을 항상 함께 둡니다."""
    image = ImageReader(str(SITE / media["src"].removeprefix("./")))
    iw, ih = image.getSize()
    scale = min((CW - 14) / iw, (height - 14) / ih)
    width, sh = iw * scale, ih * scale
    rect(M, top, CW, height, radius=10, stroke=LINE)
    c.drawImage(image, M + (CW - width) / 2, H - top - (height + sh) / 2,
                width=width, height=sh, mask="auto")
    return top + height + 13


def case(item, top, color, compact=False):
    if compact:
        # 같은 장의 두 사례는 설명 제목을 반복하지 않고 배경 처리로 구현 경험을 강조합니다.
        y = text(item["title"], M, top, CW, 16, True, color) + 12
        y = text(item["problem"], M, y, CW, 9.5) + 12
        _, height = paragraph(item["action"], CW - 28, 10)
        rect(M, y, CW, height + 28, radius=8)
        text(item["action"], M + 14, y + 14, CW - 28, 10)
        y += height + 42
        y = text("구현 위치  /  " + item["evidence"], M, y, CW, 8.3, False, MUTED) + 8
        return text(item["limit"], M, y, CW, 8.7, False, MUTED) + 20
    size = 10 if compact else 10.5
    y = text(item["title"], M, top, CW, 16 if compact else 19, True) + 16
    y = block("고려한 점", item["problem"], y, color, size)
    y = block("제가 구현한 방식", item["action"], y, color, size, background=True)
    y = text("구현 위치  /  " + item["evidence"], M, y, CW, 8.5, False, MUTED) + 14
    return text(item["limit"], M, y, CW, 9, False, MUTED) + 22


def project_page(project, media_index=0):
    color = COLORS[project["id"]]
    page(f'{project["number"]}  /  {project["name"]}  /  PROJECT', color)
    y = text(project["name"], M, 66, CW, 37, True, color, leading=44) + 8
    y = text(project["subtitle"], M, y, CW, 15, True) + 14
    meta = f'{project["type"]} · {project["teamSize"]}명  /  {project["period"]}'
    y = text(meta, M, y, CW, 9.5, False, MUTED) + 22
    y = text(project["overview"], M, y, CW, 10.6) + 17
    y = block("제가 맡은 일", project["role"], y, color, 10.5) - 6
    y = block("사용한 기술과 이유", project["technologyReason"], y, color, 10, background=True)
    # 먼저 맡은 범위와 판단을 읽은 다음, 아래 화면으로 실제 작업을 확인합니다.
    y = screenshot(project["media"][media_index], y, height=234)
    y = text(project["mediaNote"], M, y, CW, 8.7, False, MUTED) + 15
    y = text("사용 기술  /  " + " · ".join(project["stack"]), M, y, CW, 9.3, False, MUTED) + 10
    linked("프로젝트 저장소 보기", project["repo"], M, y, 9.2, color)


# 1. 인사와 사진 뒤에는 역할을 짧게 보여 주는 프로젝트 목록을 배치합니다.
page("KIM MYEONGHYEON  /  BACKEND DEVELOPER")
rect(W - M - 119, 79, 119, 157, radius=12)
portrait = ImageReader(str(SITE / profile["photo"].removeprefix("./")))
iw, ih = portrait.getSize()
c.drawImage(portrait, W - M - 112, H - 86 - 105 * ih / iw, width=105, height=105 * ih / iw, mask="auto")
y = text(profile["greeting"], M, 91, CW - 145, 20, True) + 15
y = text(profile["aspiration"], M, y, CW - 145, 18, False, MUTED) + 4
y = text(profile["introductionName"], M, y, CW - 145, 33, True, BLUE, 44) + 24
y = text(profile["intro"], M, max(y, 260), CW, 12) + 35
rule(y)
y = text("제가 참여한 프로젝트", M, y + 25, CW, 17, True) + 23
for p in projects:
    color = COLORS[p["id"]]
    rect(M, y, 34, 34, radius=8)
    text(p["number"], M + 9, y + 7, 25, 11, True, color)
    text(p["name"], M + 50, y, CW - 50, 16, True)
    bottom = text(p["previewRole"], M + 50, y + 27, CW - 50, 10, False, MUTED)
    y = max(y + 73, bottom + 22)
y = text("Java · Spring MVC / Spring Boot · MyBatis · Oracle", M, y + 1, CW, 10, True, MUTED) + 9
y = linked(profile["email"], "mailto:" + profile["email"], M, y + 8, 10.5) + 8
linked(profile["github"].removeprefix("https://"), profile["github"], M, y, 9.5, MUTED)

# 2. 서비스의 AI 기능과 개발 과정의 AI 사용을 앞에서 읽고 실제 코드와 검증으로 이어집니다.
page("AI PRACTICE  /  EDUCATION")
y = title(data["aiPractice"]["title"], size=26)
for item in data["aiPractice"]["cards"]:
    y = text(item["tool"], M, y, CW, 9, True, BLUE) + 5
    y = block(item["title"], item["text"], y, BLUE, 10, background=True) - 12
    if item.get("steps"):
        y = practice_steps(item["steps"], y)
    reference = item["reference"]
    y = linked(reference["label"], reference["url"], M, y, 9.2) + 18
y = text("학력 · 교육 · 자격", M, y, CW, 15, True) + 14
for item in data["education"]:
    y = text(item["title"] + "  /  " + item["period"], M, y, CW, 10.5, True) + 3
    y = text(item.get("shortTitle", item["subtitle"]) + (" · " + item["status"] if item["status"] else ""), M, y, CW, 9.4, False, MUTED) + 12

# 3-5. 제때약은 담당 화면과 AI 상담, 내 약 관리·커뮤니티 경험 순서로 읽습니다.
project_page(jette)
page("01  /  제때약  /  AI 상담", COLORS[jette["id"]])
y = title("AI 상담에 쓸 정보를\n서버에서 확인했습니다.", size=26)
y = flow(jette["flow"], y, COLORS[jette["id"]])
y = case(jette["cases"][0], y, COLORS[jette["id"]])
block("구현하면서 배운 점", jette["learning"], y, COLORS[jette["id"]], 10.5)
page("01  /  제때약  /  내 약 관리 · 커뮤니티", COLORS[jette["id"]])
y = title("누가 어떤 정보를\n바꿀 수 있는지 확인했습니다.", size=26)
y = case(jette["cases"][1], y, COLORS[jette["id"]], compact=True)
rule(y - 6)
y = case(jette["cases"][2], y + 12, COLORS[jette["id"]], compact=True)
text(jette["verification"], M, y, CW, 8.7, False, MUTED)

# 6-7. 여행픽은 화면 뒤에 일정 변경·권한·DB 처리 경험을 연결합니다.
project_page(travel, 1)
page("02  /  여행픽  /  일정 변경 · 데이터", COLORS[travel["id"]])
y = title("일정을 바꾸고 DB를 옮길 때\n확인한 조건입니다.", size=26)
for i, item in enumerate(travel["cases"]):
    y = text(item["title"], M, y, CW, 15, True, COLORS[travel["id"]]) + 11
    y = text(item["action"], M, y, CW, 10.3) + 11
    y = text(item["limit"], M, y, CW, 8.7, False, MUTED) + 19
    if i < len(travel["cases"]) - 1:
        rule(y - 6)
text(travel["verification"], M, y, CW, 8.7, False, MUTED)

# 8-9. 5ASIS는 메인 화면과 외부 데이터 변환·중복 제어 경험을 설명합니다.
project_page(oasis)
page("03  /  5ASIS  /  대시보드 · Steam API", COLORS[oasis["id"]])
y = title("형태가 다른 응답을\n한 화면에 연결했습니다.", size=26)
y = flow(oasis["flow"], y, COLORS[oasis["id"]])
y = case(oasis["cases"][0], y, COLORS[oasis["id"]])
y = block("구현하면서 배운 점", oasis["learning"], y, COLORS[oasis["id"]], 10.5)
text(oasis["verification"], M, y, CW, 8.7, False, MUTED)

# 10. 개발을 시작한 이야기와 프로젝트 저장소, 연락처를 모읍니다.
page("WORKING TOGETHER  /  CONTACT")
y = title("제가 배워온 과정입니다.", size=28)
for item in data["about"]:
    y = block(item["title"], item["text"], y, BLUE, 10.5)
rule(y + 2)
y = text("프로젝트 코드", M, y + 25, CW, 15, True) + 17
for p in projects:
    y = linked(p["name"] + "  /  " + p["repo"].removeprefix("https://"), p["repo"], M, y, 9.3) + 5
    if p.get("upstream"):
        y = linked("팀 원본  /  " + p["upstream"].removeprefix("https://"), p["upstream"], M + 14, y, 8.5, MUTED, CW - 14) + 12
y = linked("웹 포트폴리오에서 전체 화면 보기", "https://sudalsudal01.github.io/backend-portfolio/", M, y + 8, 10.5) + 32
y = text(profile["name"], M, y, CW, 26, True) + 13
y = linked(profile["email"], "mailto:" + profile["email"], M, y, 12) + 8
linked(profile["phone"], "tel:" + profile["phone"].replace("-", ""), M, y, 11, MUTED)

if page_number != TOTAL_PAGES:
    raise ValueError(f"예상 {TOTAL_PAGES}쪽과 생성 {page_number}쪽이 다릅니다.")
c.save()
shutil.copyfile(BASE / "content.json", SITE / "data.json")
(SITE / "assets").mkdir(exist_ok=True)
shutil.copyfile(pdf_path, SITE / "assets" / pdf_path.name)
web_output = ROOT / "output" / "web"
web_output.mkdir(parents=True, exist_ok=True)
# 배포 파일만 압축합니다. 환경변수·실사용 정보·팀 코드 전체는 포함하지 않습니다.
with zipfile.ZipFile(web_output / "kim-myeonghyeon-portfolio-web.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(SITE.rglob("*")):
        if path.is_file():
            archive.write(path, path.relative_to(SITE))
print(f"PDF: {pdf_path}")
print(f"{TOTAL_PAGES} pages / web content synchronized")
