FROM node:22-alpine
WORKDIR /app
COPY ai-mcp/package.json ai-mcp/package-lock.json ./
RUN npm ci --omit=dev
COPY ai-mcp/src ./src
COPY ai-mcp/scripts ./scripts
COPY contracts/ai-mcp/v1 /contracts/ai-mcp/v1
ENV PORT=8083 MCP_STATE_DIR=/data
RUN mkdir -p /data && chown node:node /data
EXPOSE 8083
VOLUME ["/data"]
USER node
CMD ["node", "src/server.js"]
