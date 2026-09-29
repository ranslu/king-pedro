"""King Pedro on Streamlit: serves the same single-file game inside a Streamlit page.

Deploy: share.streamlit.io -> New app -> repo ranslu/king-pedro, branch master, main file streamlit_app.py.
For the phone-installable app with an icon and offline play, use the GitHub Pages address instead
(https://ranslu.github.io/king-pedro/): Streamlit's sandboxed frame can't be installed to a home screen.
"""
from pathlib import Path
import streamlit as st
import streamlit.components.v1 as components

st.set_page_config(page_title="King Pedro", page_icon="👑", layout="wide", initial_sidebar_state="collapsed")
st.markdown(
    "<style>#MainMenu,header,footer{visibility:hidden;height:0}"
    ".block-container{padding:0!important;max-width:100%!important}</style>",
    unsafe_allow_html=True,
)
html = (Path(__file__).parent / "docs" / "index.html").read_text(encoding="utf-8")
components.html(html, height=900, scrolling=False)
