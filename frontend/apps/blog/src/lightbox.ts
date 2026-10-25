let lightboxEl: HTMLElement | null = null

function closeLightbox(): void {
  if (!lightboxEl) {
    return
  }
  lightboxEl.remove()
  lightboxEl = null
  document.removeEventListener('keydown', onLightboxKey)
}

function onLightboxKey(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    closeLightbox()
  }
}

export function openLightbox(src: string, alt = ''): void {
  if (!src) {
    return
  }
  closeLightbox()
  const wrapper = document.createElement('div')
  wrapper.className = 'bc-lightbox'
  wrapper.addEventListener('click', closeLightbox)
  const image = document.createElement('img')
  image.src = src
  image.alt = alt
  wrapper.appendChild(image)
  document.body.appendChild(wrapper)
  lightboxEl = wrapper
  document.addEventListener('keydown', onLightboxKey)
}
