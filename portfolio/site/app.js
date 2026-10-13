/* PDF와 같은 공개 JSON을 사용합니다. 모든 문구는 HTML 이스케이프 후 표시합니다. */
const e = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const tags = (items) => items.map((item) => `<span>${e(item)}</span>`).join("");
/* 문장 끝의 마침표 뒤에서 새 문단을 만듭니다. 숫자·버전·주소 안의 점은 유지합니다.
   모바일에서는 문단 안에서도 자연스럽게 줄바꿈하여 강제 한 줄로 인한 넘침을 방지합니다. */
const sentences = (value) =>
  String(value)
    .trim()
    .split(/(?<=[.!?])\s+(?=[가-힣A-Z])/u);
const prose = (value) =>
  `<div class="sentence-group">${sentences(value)
    .map((s) => `<p>${e(s)}</p>`)
    .join("")}</div>`;
const card = (label, body, style = "") =>
  `<div class="info-card ${style}"><h4>${e(label)}</h4>${prose(body)}</div>`;
async function renderPortfolio() {
  // 배포 도중 이전 JSON이 캐시에 남아도 새 화면과 섞이지 않도록 자산 버전을 맞춥니다.
  const response = await fetch("./data.json?v=20261013-profile");
  if (!response.ok) throw new Error("content unavailable");
  const { profile, projects, notes, about, skills, education } =
    await response.json();
  document.querySelector("#main").innerHTML = `
 <section class="hero" aria-labelledby="intro-title">
  <div class="hero-copy"><p class="kicker">BACKEND DEVELOPER · PORTFOLIO</p>
   <h1 id="intro-title"><span class="greeting">${e(profile.greeting ?? "안녕하세요.")}</span><span class="aspiration">${e(profile.aspiration ?? "백엔드 개발자를 꿈꾸는")}</span><span class="name-line"><em>${e(profile.name)}</em>입니다.</span></h1>
   <div class="intro">${prose(profile.intro)}</div>
   <div class="actions"><a class="button primary" href="./assets/kim-myeonghyeon-portfolio.pdf" download>지원용 PDF 내려받기 <span aria-hidden="true">↗</span></a><a class="button" href="${e(profile.github)}" target="_blank" rel="noopener noreferrer">GitHub 보기 ↗</a></div>
   <div class="hero-skills" aria-label="주요 기술">${tags(["Java", "Spring", "MyBatis", "Oracle"])}</div>
  </div>
  <aside class="profile-card" aria-label="개발자 소개와 프로젝트 바로가기">
   <div class="identity"><img class="profile-photo" src="${e(profile.photo)}" alt="${e(profile.name)} 프로필 사진" width="104" height="135"><div class="identity-copy"><p class="kicker">ABOUT ME</p><strong>${e(profile.name)}</strong><span>${e(profile.role)}</span></div></div>
   <a class="profile-email" href="mailto:${e(profile.email)}">${e(profile.email)} ↗</a>
   <div class="project-shortcuts"><p class="card-label">프로젝트 바로보기</p>${projects.map((p) => `<a href="#${e(p.id)}"><span class="shortcut-number">${e(p.number)}</span><span><strong>${e(p.name)}</strong><small>${e(p.type)}</small></span><span aria-hidden="true">↗</span></a>`).join("")}</div>
  </aside>
 </section>
 <section id="about" class="about section-block" aria-labelledby="about-title">
  <div class="section-title"><div><p class="kicker">ABOUT ME</p><h2 id="about-title">보안에 대한 관심에서,<br>백엔드 개발로.</h2></div><p class="section-description">개발을 시작하고 목표를 구체화한 과정입니다.</p></div>
  <div class="story-grid">${about.map((item, i) => `<article class="story-card"><span class="step-label">0${i + 1}</span><h3>${e(item.title)}</h3>${prose(item.text)}</article>`).join("")}</div>
 </section>
 <section id="skills" class="section-block" aria-labelledby="skills-title">
  <div class="section-title"><div><p class="kicker">SKILLS & EXPERIENCE</p><h2 id="skills-title">사용한 기술,<br>경험으로 설명합니다.</h2></div><div class="section-description"><p>프로젝트에서 사용한 기술을 분야별로 정리했습니다.</p><p>각 카드에서 실제 사용한 프로젝트로 이동할 수 있어요.</p></div></div>
  <div class="skill-grid">${skills
    .map(
      (item) =>
        `<article class="skill-card skill-${e(item.id)}"><p class="skill-label">${e(item.label)}</p><h3>${e(item.title)}</h3><div class="tags">${tags(item.items)}</div>${prose(item.usage)}<div class="skill-projects" aria-label="사용 프로젝트">${item.projectIds
          .map((id) => {
            const project = projects.find((p) => p.id === id);
            return `<a href="#${e(id)}">${e(project.name)} ↗</a>`;
          })
          .join("")}</div></article>`,
    )
    .join("")}</div>
 </section>
 <section id="projects" class="projects" aria-labelledby="projects-title">
  <div class="section-title"><div><p class="kicker">SELECTED PROJECTS</p><h2 id="projects-title">프로젝트로 소개하는<br>나의 개발 경험.</h2></div><div class="section-description"><p>담당한 기능과 개발 과정을 먼저 소개합니다.</p><p>상세 사례와 검증 기록은 펼쳐서 확인할 수 있어요.</p></div></div>
  ${projects
    .map(
      (p) => `<article id="${e(p.id)}" class="project project-${e(p.id)}">
   <div class="project-header"><span class="project-number">${e(p.number)}</span><div><div class="project-meta"><span>${e(p.type)} · ${e(p.teamSize)}명</span><span>${e(p.period)}</span><span>${e(p.scheduleStatus)}</span></div><h3>${e(p.name)}</h3><p class="subtitle">${e(p.subtitle)}</p></div><a class="repo-link" href="${e(p.repo)}" target="_blank" rel="noopener noreferrer">${p.private ? "비공개 저장소" : "코드 보기"} ↗</a></div>
   <figure class="project-preview">${p.id === "travel-pick" ? `<img src="./assets/travel-courses.jpg" alt="여행픽의 세 가지 코스를 비용과 취향으로 비교하는 로컬 시연 화면" width="1265" height="1449" loading="lazy"><figcaption>여행픽 코스 비교 · 로컬 시연 화면</figcaption>` : `<div class="architecture-preview" aria-label="${e(p.name)} 백엔드 처리 흐름"><p class="card-label">${e(p.name)} · 백엔드 처리 흐름</p><div class="architecture-steps">${p.flow.map((step, i) => `<div><span>0${i + 1}</span><strong>${e(step)}</strong></div>`).join("")}</div></div><figcaption>담당 기능의 처리 흐름 · 실제 서비스 화면이 아닌 구조 설명</figcaption>`}</figure>
   <div class="project-overview"><div class="overview-card"><h4>어떤 프로젝트인가요?</h4>${prose(p.overview)}</div><div class="role"><h4>내가 맡은 역할</h4><ul>${p.role
     .split(/\s*·\s*/u)
     .map((r) => `<li>${e(r)}</li>`)
     .join("")}</ul></div></div>
   <div class="tags" aria-label="${e(p.name)} 사용 기술">${tags(p.stack)}</div>
   <ol class="flow" aria-label="${e(p.name)} 처리 흐름">${p.flow.map((s, i) => `<li><span>0${i + 1}</span><strong>${e(s)}</strong></li>`).join("")}</ol>
   <div class="cases">${p.cases.map((item, i) => `<details><summary><span class="case-number">0${i + 1}</span><span class="summary-title">${e(item.title)}</span><b aria-hidden="true">+</b></summary><div class="case-content">${card("문제", item.problem)}${card("처리 방식", item.action, "solution-card")}${card("코드 근거", item.evidence, "evidence")}${card("범위와 한계", item.limit, "limit-card")}</div></details>`).join("")}</div>
   <div class="project-takeaways"><div class="learning-card"><h4>이 경험에서 배운 점</h4>${prose(p.learning)}</div><details class="validation"><summary><span class="summary-title">검증 기록과 확인 범위</span><b aria-hidden="true">+</b></summary>${prose(p.verification)}</details></div>
   ${p.upstream ? `<div class="upstream-note"><p>팀 이력과 기여자를 보존한 개인 포크입니다.</p><a href="${e(p.upstream)}" target="_blank" rel="noopener noreferrer">팀 원본 보기 ↗</a></div>` : ""}
   ${p.private ? '<p class="private-note">현재 비공개 저장소입니다.</p>' : ""}
  </article>`,
    )
    .join("")}
 </section>
 <section id="approach" class="approach" aria-labelledby="approach-title"><p class="kicker">HOW I BUILD</p><h2 id="approach-title">개발하며 지키는<br>세 가지 기준.</h2><div class="principles">${profile.strengths.map((item, i) => `<article><span>0${i + 1}</span><h3>${e(item.title)}</h3>${prose(item.text)}</article>`).join("")}</div><details class="notes"><summary><span class="summary-title">포트폴리오 표기 기준</span><b aria-hidden="true">+</b></summary>${notes.map(prose).join("")}</details></section>
 <section id="education" class="section-block" aria-labelledby="education-title"><div class="section-title"><div><p class="kicker">EDUCATION & CERTIFICATE</p><h2 id="education-title">배움의 과정과<br>기초를 쌓은 기록.</h2></div><p class="section-description">학력 · 교육 · 자격</p></div><div class="education-grid">${education.map((item) => `<article class="education-card" data-education="${e(item.id)}"><div class="education-meta"><span>${e(item.kind)}</span><span class="status-badge">${e(item.status)}</span></div><h3>${e(item.title)}</h3><p class="education-subtitle">${e(item.subtitle)}</p><p class="education-period">${e(item.period)}</p>${prose(item.detail)}</article>`).join("")}</div></section>
 <section id="contact" class="contact" aria-labelledby="contact-title"><p class="kicker">LET'S CONNECT</p><h2 id="contact-title">함께 일할 기회를<br>기다립니다.</h2><div class="contact-bottom"><div><strong>${e(profile.name)}</strong><p>${e(profile.role)}</p></div><a href="tel:${e(profile.phone.replaceAll("-", ""))}">${e(profile.phone)} ↗</a><a href="mailto:${e(profile.email)}">${e(profile.email)} ↗</a><a href="${e(profile.github)}" target="_blank" rel="noopener noreferrer">GitHub ↗</a></div></section>`;
}
/* 메뉴는 키보드·터치로 열고 닫습니다. 작은 화면에서 목적지로 이동하면 닫고 Escape로도 복귀합니다. */
const menuToggle = document.querySelector("#menu-toggle");
const menu = document.querySelector("#primary-nav");
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
    '<section class="loading" role="alert"><h1>내용을 불러오지 못했어요.</h1><p>연결 상태를 확인한 후 다시 불러오거나 PDF를 열어 주세요.</p><div class="actions"><a class="button primary" href="./assets/kim-myeonghyeon-portfolio.pdf">PDF 열기</a><button class="button" type="button" id="retry-content">다시 불러오기</button></div></section>';
  document
    .querySelector("#retry-content")
    .addEventListener("click", () => location.reload());
});
