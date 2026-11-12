/* 웹과 PDF가 같은 공개 콘텐츠를 사용합니다. HTML에 넣는 문구는 모두 이스케이프하여
   프로젝트 설명에 기호가 들어가도 문서 구조가 바뀌지 않도록 처리합니다. */
const e = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (character) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        character
      ],
  );
const tags = (items) => items.map((item) => `<span>${e(item)}</span>`).join("");
/* 문장별로 나누어 읽게 하되 날짜·버전의 점은 보존합니다. 줄을 억지로 맞추지 않고
   좁은 화면에서는 문장이 자연스럽게 다음 줄로 이어지도록 처리합니다. */
const prose = (value) =>
  `<div class="sentence-group">${String(value)
    .trim()
    .split(/(?<=[.!?])\s+(?=[가-힣A-Z])/u)
    .map((sentence) => `<p>${e(sentence)}</p>`)
    .join("")}</div>`;
const info = (label, body) =>
  `<section class="info-card"><h3>${e(label)}</h3>${prose(body)}</section>`;
const projectMeta = (project) =>
  `${project.type} · ${project.teamSize}명 · ${project.period}${project.scheduleStatus ? " · " + project.scheduleStatus : ""}`;
/* 아이콘은 장식용입니다. 기술 이름과 버튼 이름은 별도 텍스트로 제공하므로
   보조기술이 아이콘의 선과 모양을 읽을 필요가 없습니다. */
function skillIcon(id) {
  const paths = {
    backend: '<path d="m8 7-5 5 5 5m8-10 5 5-5 5M14 4l-4 16"/>',
    database:
      '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
    frontend:
      '<rect x="3" y="4" width="18" height="15" rx="2"/><path d="M3 9h18m-11 0v10"/>',
    tools:
      '<circle cx="6" cy="6" r="3"/><circle cx="18" cy="18" r="3"/><path d="M6 9v6a3 3 0 0 0 3 3h6m3-15v9m-4-5 4 5 4-5"/>',
  };
  return `<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[id] || paths.backend}</svg>`;
}
let content;
let returnFocus;
const dialog = document.querySelector("#detail-dialog");
const dialogTitle = document.querySelector("#detail-title");
const dialogBody = document.querySelector("#detail-body");
const closeButton = document.querySelector("#close-detail");

/* 선택 버튼을 위에 놓아 다른 장면도 바로 찾게 합니다. 실제 캡처·가상 데이터의
   촬영 조건은 갤러리 아래에 남겨 기능 검증과 화면 재현을 혼동하지 않게 합니다. */
function preview(project) {
  if (!project.media?.length) return "";
  const first = project.media[0];
  return `<section id="detail-screens" class="media-gallery" aria-label="${e(project.name)} 화면 갤러리"><div class="detail-section-head"><span class="eyebrow">SCREENS</span><h3>구현한 화면</h3></div><div class="media-choices" aria-label="화면 선택">${project.media.map((media, index) => `<button type="button" class="media-choice" data-media-project="${e(project.id)}" data-media-index="${index}" aria-pressed="${index === 0}"><span class="media-index">0${index + 1}</span><span>${e(media.title)}</span></button>`).join("")}</div><figure class="media-stage" aria-busy="true"><div class="media-frame"><a class="media-original" href="${e(first.src)}" target="_blank" rel="noopener noreferrer" aria-label="${e(first.title)} 원본 이미지 새 창으로 열기"><img src="${e(first.src)}" alt="${e(first.alt)}" width="1440" height="1000"></a><p class="media-loading" role="status">화면 이미지를 불러오는 중입니다.</p><div class="media-error" role="alert" hidden><p>화면 이미지를 불러오지 못했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.</p><button class="button" type="button" data-retry-media>이미지 다시 불러오기</button></div></div><figcaption aria-live="polite" aria-atomic="true"><strong class="media-title">${e(first.title)}</strong><p class="media-caption">${e(first.caption)}</p></figcaption></figure><p class="media-notice">${e(project.mediaNote)}</p><p class="media-hint">큰 이미지를 누르면 원본 크기로 확인할 수 있습니다.</p></section>`;
}
// 화면 이미지도 네트워크 오류가 날 수 있으므로 로딩·실패·재시도를 같은 갤러리에서 처리합니다.
function setGalleryState(gallery, state) {
  gallery
    .querySelector(".media-stage")
    .setAttribute("aria-busy", String(state === "loading"));
  gallery.querySelector(".media-loading").hidden = state !== "loading";
  gallery.querySelector(".media-error").hidden = state !== "error";
  gallery.querySelector(".media-original").hidden = state === "error";
}
function connectGallery(gallery) {
  const image = gallery.querySelector(".media-stage img");
  image.addEventListener("load", () => setGalleryState(gallery, "ready"));
  image.addEventListener("error", () => setGalleryState(gallery, "error"));
  // 브라우저 캐시에서 이미 완료된 이미지도 이벤트를 기다리지 않고 상태를 맞춥니다.
  if (image.complete)
    setGalleryState(gallery, image.naturalWidth ? "ready" : "error");
}
function projectDetail(project) {
  return `<section class="detail-intro"><p class="detail-meta">${e(projectMeta(project))}</p><p class="detail-subtitle">${e(project.subtitle)}</p>${prose(project.overview)}<div class="detail-role"><span>제가 맡은 일</span><strong>${e(project.role)}</strong></div><div class="tags detail-tags">${tags(project.stack)}</div><div class="modal-actions"><a class="button primary" href="${e(project.repo)}" target="_blank" rel="noopener noreferrer">코드 보기 ↗</a>${project.upstream ? `<a class="button" href="${e(project.upstream)}" target="_blank" rel="noopener noreferrer">팀 저장소 보기 ↗</a>` : ""}</div></section><nav class="detail-jump" aria-label="프로젝트 상세 바로가기"><button type="button" data-detail-target="detail-screens">구현 화면</button><button type="button" data-detail-target="detail-implementation">구현 과정</button><button type="button" data-detail-target="detail-learning">배운 점</button></nav><section class="highlights" aria-label="구현한 기능">${(project.highlights || []).map((highlight, index) => `<article class="highlight-card"><span class="step-label">0${index + 1}</span><h3>${e(highlight.title)}</h3><p>${e(highlight.text)}</p></article>`).join("")}</section>${preview(project)}<section id="detail-implementation" class="implementation"><div class="detail-section-head"><span class="eyebrow">BUILD LOG</span><h3>이렇게 구현하였습니다</h3></div><div class="case-list">${project.cases.map((item, index) => `<article class="case-section"><div class="case-title"><span>0${index + 1}</span><h4 class="case-heading">${e(item.title)}</h4></div><div class="case-problem"><h5 class="field-label">고민한 점</h5>${prose(item.problem)}</div><div class="case-action"><h5 class="field-label">제가 구현한 방법</h5>${prose(item.action)}</div><details class="case-evidence"><summary>관련 코드와 추가 과제</summary><div class="evidence-inner"><div class="code-reference"><h5 class="field-label">관련 코드</h5><p>${e(item.evidence)}</p></div><div><h5 class="field-label">추가로 확인할 부분</h5>${prose(item.limit)}</div></div></details></article>`).join("")}</div></section><section id="detail-learning" class="learning-section"><div class="detail-section-head"><span class="eyebrow">LEARNED</span><h3>직접 만들며 배운 점</h3></div>${prose(project.learning)}</section><details class="verification"><summary>테스트와 확인 기록</summary><div class="verification-content">${prose(project.verification)}</div></details>`;
}
function aboutDetail() {
  return `<div class="story-details">${content.about.map((item) => info(item.title, item.text)).join("")}</div><h3 class="detail-section-heading">학력 · 교육 · 자격</h3><div class="education-details">${content.education.map((item) => `<section class="info-card"><p class="detail-meta">${e(item.kind)}${item.status ? " · " + e(item.status) : ""}</p><h3>${e(item.title)}</h3><p>${e(item.subtitle)}</p><p class="education-period">${e(item.period)}</p>${prose(item.detail)}</section>`).join("")}</div><h3 class="detail-section-heading">프로젝트를 진행하며 지킨 점</h3><div class="story-details">${content.profile.strengths.map((item) => info(item.title, item.text)).join("")}</div>`;
}

/* native dialog가 배경을 비활성화합니다. 기술→프로젝트는 창을 중첩하지 않고
   내용을 교체하며, 닫을 때는 배경에서 처음 눌렀던 카드로 초점을 돌려보냅니다. */
function openDetail(kind, id, trigger) {
  returnFocus = trigger;
  if (kind === "project") {
    const project = content.projects.find((item) => item.id === id);
    if (!project) return;
    dialogTitle.textContent = project.name;
    dialogBody.innerHTML = projectDetail(project);
  } else if (kind === "skill") {
    const skill = content.skills.find((item) => item.id === id);
    if (!skill) return;
    dialogTitle.textContent = skill.title;
    dialogBody.innerHTML = `<p class="detail-meta">${e(skill.label)}</p><div class="tags detail-tags">${tags(skill.items)}</div>${info("사용 경험", skill.usage)}<h3 class="detail-section-heading">사용한 프로젝트</h3><div class="related-projects">${skill.projectIds
      .map((projectId) => {
        const project = content.projects.find((item) => item.id === projectId);
        return `<button type="button" class="related-card" data-open="project" data-id="${e(project.id)}"><strong>${e(project.name)}</strong><span>${e(project.summary)}</span><span class="card-action">프로젝트 상세 ↗</span></button>`;
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
  const gallery = dialogBody.querySelector(".media-gallery");
  if (gallery) connectGallery(gallery);
}
closeButton.addEventListener("click", () => dialog.close());
/* summary도 키보드 이동 대상에 포함합니다. 접힌 details의 내부 컨트롤은 제외하고
   첫·마지막 초점에서 Tab/Shift+Tab이 창 밖으로 빠져나가지 않도록 순환시킵니다. */
dialog.addEventListener("keydown", (event) => {
  if (event.key !== "Tab") return;
  const focusable = [
    ...dialog.querySelectorAll(
      'button:not(:disabled), a[href], summary, [tabindex="0"]',
    ),
  ].filter((element) => element.getClientRects().length > 0);
  const first = focusable[0],
    last = focusable.at(-1),
    active = document.activeElement;
  if (!first) return;
  if (event.shiftKey && active === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && active === last) {
    event.preventDefault();
    first.focus();
  } else if (!focusable.includes(active) && dialog.contains(active)) {
    // 바로가기 제목에 임시 초점이 있어도 가까운 컨트롤로 자연스럽게 이어갑니다.
    const next = event.shiftKey
      ? focusable
          .filter(
            (element) =>
              active.compareDocumentPosition(element) &
              Node.DOCUMENT_POSITION_PRECEDING,
          )
          .at(-1)
      : focusable.find(
          (element) =>
            active.compareDocumentPosition(element) &
            Node.DOCUMENT_POSITION_FOLLOWING,
        );
    event.preventDefault();
    (next || (event.shiftKey ? last : first)).focus();
  }
});
dialog.addEventListener("click", (event) => {
  if (event.target === dialog) dialog.close();
});
dialog.addEventListener("close", () => {
  document.documentElement.classList.remove("modal-open");
  if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
});
dialogBody.addEventListener("click", (event) => {
  const retry = event.target.closest("[data-retry-media]");
  if (retry) {
    const gallery = retry.closest(".media-gallery");
    const image = gallery.querySelector(".media-stage img");
    gallery
      .querySelector('.media-choice[aria-pressed="true"]')
      .focus({ preventScroll: true });
    setGalleryState(gallery, "loading");
    // 일시적인 실패 응답을 재사용하지 않도록 재시도 요청에만 캐시 구분값을 붙입니다.
    const url = new URL(gallery.querySelector(".media-original").href);
    url.searchParams.set("retry", Date.now());
    image.src = url.href;
    return;
  }
  const jump = event.target.closest("[data-detail-target]");
  if (jump) {
    const section = dialogBody.querySelector(`#${jump.dataset.detailTarget}`);
    const heading = section?.querySelector("h3, h4");
    if (section && heading) {
      // 제목은 평상시 Tab 순서에는 넣지 않고 이동한 순간만 초점을 주어 읽을 위치를 알립니다.
      heading.setAttribute("tabindex", "-1");
      heading.addEventListener(
        "blur",
        () => heading.removeAttribute("tabindex"),
        { once: true },
      );
      section.scrollIntoView({ behavior: "instant", block: "start" });
      heading.focus({ preventScroll: true });
    }
    return;
  }
  const mediaButton = event.target.closest("[data-media-index]");
  if (mediaButton) {
    const project = content.projects.find(
      (item) => item.id === mediaButton.dataset.mediaProject,
    );
    const media = project?.media?.[Number(mediaButton.dataset.mediaIndex)];
    if (!media) return;
    const gallery = mediaButton.closest(".media-gallery"),
      image = gallery.querySelector(".media-stage img"),
      link = gallery.querySelector(".media-original");
    setGalleryState(gallery, "loading");
    image.src = media.src;
    if (image.complete)
      setGalleryState(gallery, image.naturalWidth ? "ready" : "error");
    image.alt = media.alt;
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

/* 카드와 상세창은 같은 캡처를 사용합니다. 브라우저 장식은 캡처 바깥에만 놓고
   촬영 조건은 상세 갤러리에 남겨 원본 화면을 새로 만든 것처럼 표현하지 않습니다. */
const projectCover = (project) =>
  `<span class="project-cover" data-theme="${e(project.id)}"><span class="cover-label">${e(project.english)}</span><span class="cover-browser"><span class="cover-toolbar" aria-hidden="true"><i></i><i></i><i></i></span><img src="${e(project.media[0].src)}" alt="" width="1440" height="1000" loading="lazy"></span></span>`;
async function renderPortfolio() {
  const response = await fetch("./data.json?v=20261112-personal");
  if (!response.ok) throw new Error("content unavailable");
  content = await response.json();
  const { profile, projects, about, skills, education } = content;
  document.querySelector("#main").innerHTML = `
  <section class="hero" aria-labelledby="intro-title"><div class="hero-copy"><p class="kicker"><span class="kicker-line"></span>BACKEND DEVELOPER · PORTFOLIO</p><h1 id="intro-title"><span class="greeting">${e(profile.greeting)}</span><span class="aspiration">${e(profile.aspiration)}</span><span class="name-line"><em>${e(profile.name)}</em>입니다.</span></h1><div class="intro">${prose(profile.intro)}</div><div class="hero-tech" aria-label="주요 사용 기술">${tags(["Java", "Spring", "Oracle"])}</div><div class="actions"><a class="button primary" href="#projects">프로젝트 보기 ↓</a><a class="button" href="./assets/kim-myeonghyeon-portfolio.pdf" download>PDF 내려받기 ↗</a></div></div><aside class="profile-card"><div class="profile-top"><img class="profile-photo" src="${e(profile.photo)}" alt="${e(profile.name)} 프로필 사진" width="104" height="135"><div><p class="eyebrow">ABOUT ME</p><strong>${e(profile.name)}</strong><p>${e(profile.role)}</p><a href="${e(profile.github)}" target="_blank" rel="noopener noreferrer">GitHub ↗</a></div></div><div class="profile-projects" aria-label="프로젝트 미리보기"><p>제가 만든 서비스</p>${projects.map((project) => `<button type="button" class="profile-project" data-open="project" data-id="${e(project.id)}" aria-haspopup="dialog" aria-label="${e(project.name)} 프로젝트 미리보기"><span class="profile-number">${e(project.number)}</span><span><strong>${e(project.name)}</strong><small>${e(project.previewRole)}</small></span><span aria-hidden="true">↗</span></button>`).join("")}</div></aside></section>
  <section id="about" class="overview-section" aria-labelledby="about-title"><div class="section-heading"><div><p class="kicker">ABOUT ME</p><h2 id="about-title">백엔드를 선택한 과정</h2></div><button class="button" type="button" data-open="about" aria-haspopup="dialog" aria-label="소개 상세 보기">소개 상세 보기 ↗</button></div><div class="about-grid">${about.map((item, index) => `<article class="story-card"><span class="step-label">0${index + 1}</span><h3>${e(item.title)}</h3><p>${e(item.summary)}</p></article>`).join("")}</div><div id="education" class="education-grid" aria-label="학력·교육·자격">${education.map((item) => `<article class="education-card"><p class="card-label">${e(item.kind)}${item.status ? " · " + e(item.status) : ""}</p><h3>${e(item.title)}</h3><p>${e(item.shortTitle ?? item.subtitle)}</p><p class="education-period">${e(item.period)}</p></article>`).join("")}</div></section>
  <section id="skills" class="overview-section" aria-labelledby="skills-title"><div class="section-heading"><div><p class="kicker">SKILLS</p><h2 id="skills-title">프로젝트에서 사용한 기술</h2></div></div><div class="skill-grid">${skills.map((item) => `<button type="button" class="summary-card skill-card" data-open="skill" data-id="${e(item.id)}" aria-haspopup="dialog" aria-label="${e(item.label)} 사용 경험 상세 보기"><span class="skill-icon">${skillIcon(item.id)}</span><span class="skill-content"><span class="card-label">${e(item.label)}</span><strong class="card-title">${e(item.title)}</strong><span class="tags">${tags(item.items)}</span><span class="card-action">사용 경험 보기</span></span><span class="skill-arrow" aria-hidden="true">↗</span></button>`).join("")}</div></section>
  <section id="projects" class="overview-section" aria-labelledby="projects-title"><div class="section-heading"><div><p class="kicker">SELECTED PROJECTS</p><h2 id="projects-title">직접 만들며 쌓은 경험</h2></div><p class="section-caption">제때약 · 여행픽 · 5ASIS</p></div><div class="project-grid">${projects.map((project) => `<button id="${e(project.id)}" class="summary-card project-card" type="button" data-open="project" data-id="${e(project.id)}" aria-haspopup="dialog" aria-label="${e(project.name)} 상세 보기">${projectCover(project)}<span class="project-card-body"><span class="project-top"><span class="project-number">${e(project.number)}</span><span class="card-label">${e(project.type)} · ${e(project.teamSize)}명</span></span><strong class="card-title">${e(project.name)}</strong><span class="project-period">${e(project.period)}</span><span class="card-summary">${e(project.summary)}</span><span class="role-summary">${e(project.previewRole)}</span><span class="tags">${tags(project.stack.slice(0, 3))}</span><span class="card-action">프로젝트 상세 보기 ↗</span></span></button>`).join("")}</div></section>
  <section id="contact" class="contact" aria-labelledby="contact-title"><div><p class="eyebrow">CONTACT</p><h2 id="contact-title">읽어주셔서 감사합니다.</h2><p>제가 구현한 내용에 대해 더 이야기 나누고 싶습니다.</p></div><div class="contact-links"><a href="mailto:${e(profile.email)}">${e(profile.email)} ↗</a><a href="tel:${e(profile.phone.replaceAll("-", ""))}">${e(profile.phone)} ↗</a><a href="${e(profile.github)}" target="_blank" rel="noopener noreferrer">GitHub ↗</a></div></section>`;
  document.querySelector("#main").addEventListener("click", (event) => {
    const button = event.target.closest("[data-open]");
    if (button) openDetail(button.dataset.open, button.dataset.id, button);
  });
  const links = [...document.querySelectorAll("#primary-nav a")];
  const sections = [
    ...document.querySelectorAll("#about,#skills,#projects,#contact"),
  ];
  let navigationFrame;
  // 마지막 영역은 페이지가 짧으면 관찰 화면의 중앙까지 올라오지 못합니다.
  // 고정 헤더 아래의 실제 위치와 문서 하단 도달 여부로 현재 메뉴를 정합니다.
  const updateNavigation = () => {
    navigationFrame = undefined;
    if (dialog.open) return;
    const atBottom =
      window.scrollY > 0 &&
      Math.ceil(window.scrollY + window.innerHeight) >=
        document.documentElement.scrollHeight - 2;
    const readingLine =
      document.querySelector(".header").getBoundingClientRect().bottom + 48;
    const current = atBottom
      ? sections.at(-1)
      : sections
          .filter(
            (section) => section.getBoundingClientRect().top <= readingLine,
          )
          .at(-1);
    links.forEach((link) => {
      if (link.hash === `#${current?.id}`)
        link.setAttribute("aria-current", "location");
      else link.removeAttribute("aria-current");
    });
  };
  // 스크롤 이벤트마다 레이아웃을 반복 계산하지 않고 화면 프레임당 한 번만 검사합니다.
  const scheduleNavigation = () => {
    if (navigationFrame === undefined)
      navigationFrame = requestAnimationFrame(updateNavigation);
  };
  window.addEventListener("scroll", scheduleNavigation, { passive: true });
  window.addEventListener("resize", scheduleNavigation);
  window.addEventListener("hashchange", scheduleNavigation);
  dialog.addEventListener("close", scheduleNavigation);
  scheduleNavigation();
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
