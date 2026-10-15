/* 공개 JSON은 PDF와 공유합니다. 모든 사용자 문구는 HTML 이스케이프 후 출력합니다. */
const e = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const tags = (items) => items.map((item) => `<span>${e(item)}</span>`).join("");
/* 문장별 문단을 만들되 날짜·버전의 점은 보존합니다. 긴 문장은 화면 폭에 맞춰 자연스럽게 흐릅니다. */
const prose = (value) =>
  `<div class="sentence-group">${String(value)
    .trim()
    .split(/(?<=[.!?])\s+(?=[가-힣A-Z])/u)
    .map((s) => `<p>${e(s)}</p>`)
    .join("")}</div>`;
const info = (label, body) =>
  `<section class="info-card"><h3>${e(label)}</h3>${prose(body)}</section>`;
const projectMeta = (p) =>
  `${p.type} · ${p.teamSize}명 · ${p.period}${p.scheduleStatus ? " · " + p.scheduleStatus : ""}`;
let content;
let returnFocus;
const dialog = document.querySelector("#detail-dialog");
const dialogTitle = document.querySelector("#detail-title");
const dialogBody = document.querySelector("#detail-body");
const closeButton = document.querySelector("#close-detail");

/* 실제 서비스 캡처와 구조 설명을 명확히 구분합니다. 임의의 시연 화면을 생성하지 않습니다. */
function preview(project) {
  if (project.media?.length) {
    const first = project.media[0];
    return `<section class="media-gallery" aria-label="${e(project.name)} 화면 갤러리"><h3>핵심 기능 화면</h3><p class="media-notice">${e(project.mediaNote)}</p><figure class="media-stage"><a class="media-original" href="${e(first.src)}" target="_blank" rel="noopener noreferrer" aria-label="${e(first.title)} 원본 이미지 새 창으로 열기"><img src="${e(first.src)}" alt="${e(first.alt)}" width="1440" height="1000"></a><figcaption><strong class="media-title">${e(first.title)}</strong><p class="media-caption">${e(first.caption)}</p></figcaption></figure><div class="media-choices" aria-label="화면 선택">${project.media.map((m, i) => `<button type="button" class="media-choice" data-media-project="${e(project.id)}" data-media-index="${i}" aria-pressed="${i === 0}"><img src="${e(m.src)}" alt="" width="160" height="110" loading="lazy"><span>${e(m.title)}</span></button>`).join("")}</div><p class="media-hint">큰 이미지를 누르면 원본 크기로 확인할 수 있습니다.</p></section>`;
  }
  if (project.id === "travel-pick")
    return `<figure class="project-preview"><img src="./assets/travel-courses.jpg" alt="여행픽 세 코스 비교의 로컬 시연 화면" width="1265" height="1449"><figcaption>코스 비교 · 로컬 시연 화면</figcaption></figure>`;
  return `<figure class="project-preview"><div class="architecture-steps">${project.flow.map((step, i) => `<div><span>0${i + 1}</span><strong>${e(step)}</strong></div>`).join("")}</div><figcaption>담당 백엔드 처리 흐름 · 구조 설명</figcaption></figure>`;
}
function projectDetail(project) {
  return `<p class="detail-meta">${e(projectMeta(project))}</p><p class="detail-subtitle">${e(project.subtitle)}</p>${preview(project)}<div class="detail-grid">${info("프로젝트 소개", project.overview)}${info("담당 역할", project.role)}</div><div class="tags detail-tags">${tags(project.stack)}</div><div class="case-list">${project.cases.map((item, i) => `<section class="case-section"><h3 class="case-heading">0${i + 1} · ${e(item.title)}</h3><div class="detail-grid">${info("문제", item.problem)}${info("처리 방식", item.action)}${info("코드 근거", item.evidence)}${info("범위와 한계", item.limit)}</div></section>`).join("")}</div><div class="detail-grid">${info("배운 점", project.learning)}${info("검증 기록", project.verification)}</div><div class="modal-actions"><a class="button primary" href="${e(project.repo)}" target="_blank" rel="noopener noreferrer">코드 보기 ↗</a>${project.upstream ? `<a class="button" href="${e(project.upstream)}" target="_blank" rel="noopener noreferrer">팀 원본 보기 ↗</a>` : ""}</div>${project.upstream ? '<p class="source-note">팀 이력과 기여자를 보존한 개인 포크입니다.</p>' : ""}`;
}
function aboutDetail() {
  return `<div class="story-details">${content.about.map((item) => info(item.title, item.text)).join("")}</div><h3 class="detail-section-heading">학력 · 교육 · 자격</h3><div class="education-details">${content.education.map((item) => `<section class="info-card"><p class="detail-meta">${e(item.kind)}${item.status ? " · " + e(item.status) : ""}</p><h3>${e(item.title)}</h3><p>${e(item.subtitle)}</p><p class="education-period">${e(item.period)}</p>${prose(item.detail)}</section>`).join("")}</div><h3 class="detail-section-heading">개발 기준</h3><div class="story-details">${content.profile.strengths.map((item) => info(item.title, item.text)).join("")}</div>`;
}

/* native dialog가 배경을 비활성화하고 포커스를 가둡니다. 배경 스크롤은 잠그고 닫은 뒤 원래 카드로 포커스를 돌립니다. */
function openDetail(kind, id, trigger) {
  returnFocus = trigger;
  if (kind === "project") {
    const project = content.projects.find((p) => p.id === id);
    if (!project) return;
    dialogTitle.textContent = project.name;
    dialogBody.innerHTML = projectDetail(project);
  } else if (kind === "skill") {
    const skill = content.skills.find((s) => s.id === id);
    if (!skill) return;
    dialogTitle.textContent = skill.title;
    dialogBody.innerHTML = `<p class="detail-meta">${e(skill.label)}</p><div class="tags detail-tags">${tags(skill.items)}</div>${info("사용 경험", skill.usage)}<h3 class="detail-section-heading">적용 프로젝트</h3><div class="related-projects">${skill.projectIds
      .map((projectId) => {
        const p = content.projects.find((p) => p.id === projectId);
        return `<button type="button" class="related-card" data-open="project" data-id="${e(p.id)}"><strong>${e(p.name)}</strong><span>${e(p.summary)}</span><span class="card-action">프로젝트 상세 ↗</span></button>`;
      })
      .join("")}</div>`;
  } else {
    dialogTitle.textContent = "김명현 소개";
    dialogBody.innerHTML = aboutDetail();
  }
  document.documentElement.classList.add("modal-open");
  if (!dialog.open) dialog.showModal();
  dialogBody.scrollTop = 0;
  closeButton.focus();
}
closeButton.addEventListener("click", () => dialog.close());
/* 브라우저 주소창으로 포커스가 빠지는 경우까지 막아 Tab/Shift+Tab을 창 안에서 순환시킵니다.
   스크롤 본문도 초점 대상에 포함해 키보드 방향키·PageDown으로 긴 내용을 읽을 수 있게 합니다. */
dialog.addEventListener("keydown", (event) => {
  if (event.key !== "Tab") return;
  const focusable = [
    ...dialog.querySelectorAll(
      'button:not(:disabled), a[href], [tabindex="0"]',
    ),
  ].filter((element) => element.getClientRects().length > 0);
  const first = focusable[0];
  const last = focusable.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
});
dialog.addEventListener("click", (event) => {
  if (event.target === dialog) dialog.close();
});
dialog.addEventListener("close", () => {
  document.documentElement.classList.remove("modal-open");
  if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
});
/* 모달 안의 관련 프로젝트를 누르면 창을 중첩하지 않고 내용을 교체합니다. 복귀 대상은 배경의 원래 카드로 유지합니다. */
dialogBody.addEventListener("click", (event) => {
  // 같은 상세창 안에서 이미지만 교체한다. 버튼 초점을 유지해 키보드로 다음 화면을 고를 수 있다.
  const mediaButton = event.target.closest("[data-media-index]");
  if (mediaButton) {
    const project = content.projects.find(
      (p) => p.id === mediaButton.dataset.mediaProject,
    );
    const media = project?.media?.[Number(mediaButton.dataset.mediaIndex)];
    if (!media) return;
    const gallery = mediaButton.closest(".media-gallery");
    const image = gallery.querySelector(".media-stage img");
    image.src = media.src;
    image.alt = media.alt;
    const link = gallery.querySelector(".media-original");
    link.href = media.src;
    link.setAttribute(
      "aria-label",
      `${media.title} 원본 이미지 새 창으로 열기`,
    );
    gallery.querySelector(".media-title").textContent = media.title;
    gallery.querySelector(".media-caption").textContent = media.caption;
    gallery
      .querySelectorAll(".media-choice")
      .forEach((button) =>
        button.setAttribute("aria-pressed", String(button === mediaButton)),
      );
    return;
  }
  const button = event.target.closest("[data-open]");
  if (button) openDetail(button.dataset.open, button.dataset.id, returnFocus);
});

async function renderPortfolio() {
  const response = await fetch("./data.json?v=20261015-media");
  if (!response.ok) throw new Error("content unavailable");
  content = await response.json();
  const { profile, projects, about, skills, education } = content;
  document.querySelector("#main").innerHTML = `
 <section class="hero" aria-labelledby="intro-title"><div><p class="kicker">BACKEND DEVELOPER · PORTFOLIO</p><h1 id="intro-title"><span class="greeting">${e(profile.greeting)}</span><span class="aspiration">${e(profile.aspiration)}</span><span><em>${e(profile.name)}</em>입니다.</span></h1><div class="intro">${prose(profile.intro)}</div><div class="actions"><a class="button primary" href="./assets/kim-myeonghyeon-portfolio.pdf" download>PDF 내려받기 ↗</a><a class="button" href="${e(profile.github)}" target="_blank" rel="noopener noreferrer">GitHub ↗</a></div></div><aside class="profile-card"><img class="profile-photo" src="${e(profile.photo)}" alt="${e(profile.name)} 프로필 사진" width="104" height="135"><div><p class="kicker">ABOUT ME</p><strong>${e(profile.name)}</strong><p>${e(profile.role)}</p><a href="mailto:${e(profile.email)}">${e(profile.email)}</a></div></aside></section>
 <section id="about" class="overview-section" aria-labelledby="about-title"><div class="section-heading"><div><p class="kicker">ABOUT ME</p><h2 id="about-title">나의 소개</h2></div><button class="button" type="button" data-open="about" aria-haspopup="dialog" aria-label="소개 상세 보기">소개 상세 보기 ↗</button></div><div class="about-grid">${about.map((item, i) => `<article class="story-card"><span class="step-label">0${i + 1}</span><h3>${e(item.title)}</h3><p>${e(item.summary)}</p></article>`).join("")}</div><div id="education" class="education-grid" aria-label="학력·교육·자격">${education.map((item) => `<article class="education-card"><p class="card-label">${e(item.kind)}${item.status ? " · " + e(item.status) : ""}</p><h3>${e(item.title)}</h3><p>${e(item.shortTitle ?? item.subtitle)}</p><p class="education-period">${e(item.period)}</p></article>`).join("")}</div></section>
 <section id="skills" class="overview-section" aria-labelledby="skills-title"><div class="section-heading"><div><p class="kicker">SKILLS</p><h2 id="skills-title">사용 기술</h2></div></div><div class="skill-grid">${skills.map((item) => `<button type="button" class="summary-card skill-card" data-open="skill" data-id="${e(item.id)}" aria-haspopup="dialog" aria-label="${e(item.label)} 사용 경험 상세 보기"><span class="card-label">${e(item.label)}</span><strong class="card-title">${e(item.title)}</strong><span class="tags">${tags(item.items)}</span><span class="card-action">사용 경험 보기 ↗</span></button>`).join("")}</div></section>
 <section id="projects" class="overview-section" aria-labelledby="projects-title"><div class="section-heading"><div><p class="kicker">SELECTED PROJECTS</p><h2 id="projects-title">프로젝트</h2></div><p class="section-caption">제때약 · 여행픽 · 5ASIS</p></div><div class="project-grid">${projects.map((p) => `<button id="${e(p.id)}" class="summary-card project-card" type="button" data-open="project" data-id="${e(p.id)}" aria-haspopup="dialog" aria-label="${e(p.name)} 상세 보기"><span class="project-top"><span class="project-number">${e(p.number)}</span><span class="card-label">${e(p.type)} · ${e(p.teamSize)}명</span></span><strong class="card-title">${e(p.name)}</strong><span class="project-period">${e(p.period)}</span><span class="card-summary">${e(p.summary)}</span><span class="role-summary">${e(p.previewRole)}</span><span class="tags">${tags(p.stack.slice(0, 3))}</span><span class="card-action">프로젝트 상세 보기 ↗</span></button>`).join("")}</div></section>
 <section id="contact" class="contact" aria-labelledby="contact-title"><p class="kicker">CONTACT</p><h2 id="contact-title">함께 일할 기회를 기다립니다.</h2><div class="contact-links"><a href="tel:${e(profile.phone.replaceAll("-", ""))}">${e(profile.phone)} ↗</a><a href="mailto:${e(profile.email)}">${e(profile.email)} ↗</a><a href="${e(profile.github)}" target="_blank" rel="noopener noreferrer">GitHub ↗</a></div></section>`;
  document.querySelector("#main").addEventListener("click", (event) => {
    const button = event.target.closest("[data-open]");
    if (button) openDetail(button.dataset.open, button.dataset.id, button);
  });
}
const menuToggle = document.querySelector("#menu-toggle"),
  menu = document.querySelector("#primary-nav");
const closeMenu = () => {
  menuToggle.setAttribute("aria-expanded", "false");
  menu.classList.remove("is-open");
};
menuToggle.addEventListener("click", () => {
  const expanded = menuToggle.getAttribute("aria-expanded") === "true";
  menuToggle.setAttribute("aria-expanded", String(!expanded));
  menu.classList.toggle("is-open", !expanded);
});
menu.addEventListener("click", (event) => {
  if (event.target.closest("a")) closeMenu();
});
document.addEventListener("keydown", (event) => {
  if (
    event.key === "Escape" &&
    menuToggle.getAttribute("aria-expanded") === "true"
  ) {
    closeMenu();
    menuToggle.focus();
  }
});
renderPortfolio().catch(() => {
  document.querySelector("#main").innerHTML =
    '<section class="loading" role="alert"><h1>내용을 불러오지 못했습니다.</h1><p>연결 상태를 확인한 후 다시 불러오거나 PDF를 확인할 수 있습니다.</p><div class="actions"><a class="button primary" href="./assets/kim-myeonghyeon-portfolio.pdf">PDF 열기</a><button id="retry-content" class="button" type="button">다시 불러오기</button></div></section>';
  document
    .querySelector("#retry-content")
    .addEventListener("click", () => location.reload());
});
