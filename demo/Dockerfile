FROM python:3.13-alpine
WORKDIR /app
RUN addgroup -S simulator && adduser -S -G simulator simulator \
    && mkdir /data && chown simulator:simulator /data
COPY --chown=simulator:simulator simulator.py ./
COPY --chown=simulator:simulator fixtures ./fixtures
USER simulator
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2)"
CMD ["python", "simulator.py"]
