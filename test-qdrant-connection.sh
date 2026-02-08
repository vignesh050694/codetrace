#!/bin/bash
echo "==================================="
echo "Testing Qdrant Connection"
echo "==================================="
echo ""
QDRANT_HOST="103.191.208.233"
# Test HTTP API (port 6333)
echo "1. Testing HTTP API (port 6333)..."
HTTP_RESULT=$(curl -s -o /dev/null -w "%{http_code}" http://${QDRANT_HOST}:6333/collections 2>/dev/null)
if [ "$HTTP_RESULT" = "200" ]; then
    echo "   ✅ HTTP API is accessible"
    curl -s http://${QDRANT_HOST}:6333/collections | python3 -m json.tool 2>/dev/null || echo "   Collections: $(curl -s http://${QDRANT_HOST}:6333/collections)"
else
    echo "   ❌ HTTP API not accessible (HTTP $HTTP_RESULT)"
fi
echo ""
# Test gRPC port (6334)
echo "2. Testing gRPC port (6334)..."
if command -v nc &> /dev/null; then
    timeout 3 nc -zv ${QDRANT_HOST} 6334 2>&1 | grep -q "succeeded" && echo "   ✅ gRPC port is open" || echo "   ❌ gRPC port is not accessible"
elif command -v telnet &> /de#!/bin/bash
echo "==================================="
echo "Testing Qdrant Connectionhoecho "====RPecho "Testing Qdrant Connection"
echo "== iecho "=================    echo "echo ""
QDRANT_HOST="103.191.208.233"
# TavQDRANTe)# Test HTTP API (port 6333)
meecho "1. Testing HTTP API kiHTTP_RESULT=$(curl -s -o /dev/null -w "%RAif [ "$HTTP_RESULT" = "200" ]; then
    echo "   ✅ HTTP API is accessible"
    curl -s http://${QDRAN      echo "   ✅ HTTP API is accesLL    curl -s http://${QDRANT_HOST}:6333/"
else
    echo "   ❌ HTTP API not accessible (HTTP $HTTP_RESULT)"
fi
echo ""
# Test gRPC port (6334)
echo "2. Testing gRPC port (6334)..."
if command -v nc &> ==   ==fi
echo ""
# Test gRPC port (6334)
echo "2. Testing gRPC poroeen'# Testho "  - Restart Qdrant with both ports: docker-compose up -d qdr    timeout 3 nc -zv ${QDRANT_HOSTo elif command -v telnet &> /de#!/bin/bash
echo "==================================="
echo "Testing Qdrant Connectionhoecho "====RPeag/test-connection"
echo ""
